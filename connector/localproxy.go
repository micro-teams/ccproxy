// The connector's local MITM-splitting proxy (2026-09-15): the machine's HTTPS_PROXY now points at
// 127.0.0.1:localProxyPort instead of dialling the backend directly. This process splits by CONNECT
// target: Anthropic-domain traffic rides a MultiPath redundant stream to the backend's origin (the
// SAME wire protocol — CONNECT line + Proxy-Authorization — that ProxyServer has always read;
// nothing about that protocol changes), and everything else (npm, git, WebFetch, any other tool
// call Claude Code makes) dials straight out from the machine's own network, never touching the
// server. This solves two things at once: non-Anthropic traffic (including large files) no longer
// round-trips through ccproxy, and the real proxyUser/proxyPassword no longer sits in an env var
// every child process of Claude Code can read — this process holds it itself and attaches it to
// each Anthropic-bound CONNECT, reading it fresh from its own machine-local file every time (never
// cached in memory, so a credential rotation takes effect on the very next request with nothing to
// restart).
package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/user"
	"path/filepath"
	"strings"
	"sync"
	"time"

	multipath "github.com/micro-teams/multipath/go"
)

// localProxyPort must match CCProxyConfig.Engine.localProxyPort (backend/.../CCProxyConfig.kt).
const localProxyPort = 38091

// proxyService is the name the backend's origin registers (see origin/src/main/kotlin/.../Main.kt).
const proxyService = "proxy"

// anthropicDomains mirrors dataplane.Dataplane.mitmDomains — the exact set ProxyServer MITMs on the
// server side. Anything outside this set was always tunnelled DIRECT server-side too; splitting it
// locally instead just moves where that direct tunnel happens, so behavior is unchanged, only
// relocated off the server.
var anthropicDomains = map[string]bool{
	"api.anthropic.com":   true,
	"platform.claude.com": true,
}

// credentialPath is where ConnectorLoginOrchestrator.writeProxyCredential writes proxyUser/
// proxyPassword — a machine-local file, never the env Claude Code's child processes inherit.
//
// Resolved via the passwd database, NOT $HOME: this process runs with HOME unset (see
// ConnectorLoginOrchestrator's own comments on the same fact, and BackendApplication's
// resolveHome(), which gets the machine's real home from a LOGIN shell for exactly this reason).
// os.UserHomeDir falls back to $HOME on error, which is equally empty here — that previously
// resolved to the relative path ".config/ccproxy-connector/proxy-credential.json", silently never
// matching where the backend actually wrote the file.
func credentialPath() string {
	return filepath.Join(homeDir(), ".config", "ccproxy-connector", "proxy-credential.json")
}

// homeDir is a var, not a plain call, so tests can override it without touching the real HOME env
// or the actual current user's home directory.
var homeDir = func() string {
	if u, err := user.Current(); err == nil && u.HomeDir != "" {
		return u.HomeDir
	}
	return os.Getenv("HOME")
}

type proxyCredential struct {
	ProxyUser     string `json:"proxyUser"`
	ProxyPassword string `json:"proxyPassword"`
}

// readCredential is read fresh on every Anthropic-bound CONNECT rather than cached: the file is a
// few bytes, local disk, and this makes a credential rotation take effect immediately with nothing
// to signal or restart.
func readCredential() (proxyCredential, error) {
	data, err := os.ReadFile(credentialPath())
	if err != nil {
		return proxyCredential{}, fmt.Errorf("read proxy credential: %w", err)
	}
	var c proxyCredential
	if err := json.Unmarshal(data, &c); err != nil {
		return proxyCredential{}, fmt.Errorf("parse proxy credential: %w", err)
	}
	if c.ProxyUser == "" || c.ProxyPassword == "" {
		return proxyCredential{}, fmt.Errorf("proxy credential file is missing proxyUser/proxyPassword")
	}
	return c, nil
}

// localProxy owns the substrate connection (brought up lazily, redialled on death) and the local
// listener machine tooling connects to.
type localProxy struct {
	apiBase string

	mu     sync.Mutex
	client *multipath.Client
}

// runLocalProxy listens until ctx is cancelled. Errors starting the listener are fatal to this
// goroutine (logged by the caller); a substrate dial failure is not — it retries per-CONNECT so a
// machine that boots before the network is up still eventually reaches Anthropic.
func runLocalProxy(ctx context.Context, apiBase string, logf func(format string, args ...any)) error {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", localProxyPort))
	if err != nil {
		return fmt.Errorf("local proxy listen: %w", err)
	}
	defer ln.Close()
	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	lp := &localProxy{apiBase: apiBase}
	logf("ccproxy: local proxy listening on 127.0.0.1:%d (Anthropic domains -> multipath, everything else -> direct)", localProxyPort)

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			logf("ccproxy: local proxy accept: %v", err)
			continue
		}
		go lp.handle(ctx, conn, logf)
	}
}

func (lp *localProxy) handle(ctx context.Context, rawConn net.Conn, logf func(format string, args ...any)) {
	defer rawConn.Close()
	br := bufio.NewReader(rawConn)
	req, err := http.ReadRequest(br)
	if err != nil {
		logf("ccproxy: local proxy: read request: %v", err)
		return
	}
	logf("ccproxy: local proxy: %s %s", req.Method, req.Host)
	// http.ReadRequest's bufio.Reader can read (and buffer) bytes past the CONNECT headers in the
	// same syscall — e.g. a client that pipelines its TLS ClientHello right behind the CONNECT
	// without waiting for "200 Connection Established". Splicing the raw conn from here on would
	// silently drop whatever br already buffered, leaving the TLS handshake missing its first
	// bytes and hanging forever with no error on either side. conn wraps rawConn so every read
	// downstream goes through br first (draining anything buffered), then the underlying socket.
	conn := &bufConn{Conn: rawConn, r: br}
	if req.Method != http.MethodConnect {
		// The machine's HTTPS_PROXY is only ever used for CONNECT (TLS) traffic; anything else is
		// not a shape this proxy exists to handle.
		_, _ = conn.Write([]byte("HTTP/1.1 405 Method Not Allowed\r\n\r\n"))
		return
	}
	host := req.Host
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}

	if anthropicDomains[host] {
		lp.handleAnthropic(ctx, conn, req, logf)
		return
	}
	handleDirect(conn, req.Host, logf)
}

// bufConn is rawConn with reads routed through br (which may already hold buffered bytes read past
// the parsed request) instead of the socket directly. Write/Close still go straight to rawConn.
type bufConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *bufConn) Read(p []byte) (int, error) { return c.r.Read(p) }

// bufReadWriteCloser is the io.ReadWriteCloser analogue of bufConn, for wrapping a *multipath.
// MuxStream (which is not a net.Conn) once a bufio.Reader has already read past a framing line.
type bufReadWriteCloser struct {
	io.ReadWriteCloser
	r *bufio.Reader
}

func (c *bufReadWriteCloser) Read(p []byte) (int, error) { return c.r.Read(p) }

// handleDirect dials the target straight from this machine's own network — no server involved at
// all, matching what a client outside any proxy would do.
func handleDirect(conn net.Conn, targetHostPort string, logf func(format string, args ...any)) {
	up, err := net.DialTimeout("tcp", targetHostPort, 15*time.Second)
	if err != nil {
		logf("ccproxy: local proxy: direct dial %s: %v", targetHostPort, err)
		_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	defer up.Close()
	if _, err := conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		return
	}
	splice(conn, up)
}

// handleAnthropic relays the CONNECT over a MultiPath stream to the backend's origin, which splices
// it straight to the SAME ProxyServer:3128 a machine used to dial directly — the wire protocol
// (CONNECT line + Proxy-Authorization) is unchanged, only how it gets there. Proxy-Authorization is
// always attached by this process from its own stored credential, discarding whatever (if anything)
// the local caller sent — Claude Code's settings.json HTTPS_PROXY carries no secret any more.
func (lp *localProxy) handleAnthropic(ctx context.Context, conn net.Conn, req *http.Request, logf func(format string, args ...any)) {
	cred, err := readCredential()
	if err != nil {
		logf("ccproxy: local proxy: %v", err)
		_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	client, err := lp.substrate(ctx, logf)
	if err != nil {
		logf("ccproxy: local proxy: substrate unavailable: %v", err)
		_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	st, err := client.Open(proxyService, nil)
	if err != nil {
		// The substrate itself may have died between dial and open; drop it so the next request
		// redials rather than repeatedly opening streams on a corpse.
		lp.dropSubstrate(client)
		logf("ccproxy: local proxy: open stream: %v", err)
		_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	defer st.Close()

	auth := "Basic " + base64.StdEncoding.EncodeToString([]byte(cred.ProxyUser+":"+cred.ProxyPassword))
	if _, err := fmt.Fprintf(st, "CONNECT %s HTTP/1.1\r\nHost: %s\r\nProxy-Authorization: %s\r\n\r\n", req.Host, req.Host, auth); err != nil {
		logf("ccproxy: local proxy: write CONNECT: %v", err)
		return
	}
	// ProxyServer on the other end of this stream speaks the same CONNECT protocol our own local
	// client does — it replies with its OWN status line (normally "HTTP/1.1 200 Connection
	// Established\r\n\r\n") before any TLS bytes. That response has to be read and consumed here,
	// not forwarded: splicing it straight through (as if st were a raw target socket, the way
	// handleDirect's target is) would inject those literal ASCII bytes into what the local client
	// expects to be the start of a TLS record, breaking the handshake instantly. Read origin's
	// status line, and only echo our own 200 to the local client once we know it actually got one.
	stBr := bufio.NewReader(st)
	if err := readOriginConnectResponse(stBr); err != nil {
		logf("ccproxy: local proxy: origin response for %s: %v", req.Host, err)
		_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	if _, err := conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		return
	}
	logf("ccproxy: local proxy: %s riding the substrate", req.Host)
	// stConn wraps st so any bytes stBr already buffered past the status line (e.g. the start of
	// origin's TLS bytes, read in the same syscall as the status line) aren't lost — same reasoning
	// as bufConn above, mirrored for the origin side of this relay.
	stConn := &bufReadWriteCloser{ReadWriteCloser: st, r: stBr}
	toOrigin, fromOrigin := splice(conn, stConn)
	logf("ccproxy: local proxy: %s stream closed (%d bytes to origin, %d bytes from origin)", req.Host, toOrigin, fromOrigin)
}

// readOriginConnectResponse reads and validates the CONNECT status line ProxyServer sends before
// any TLS bytes, then drains the blank line terminating it (there are no other headers today, but
// draining to the blank line is what correctly frames any CONNECT response). Returns an error if
// the status line can't be read or doesn't report success.
func readOriginConnectResponse(r *bufio.Reader) error {
	statusLine, err := r.ReadString('\n')
	if err != nil {
		return fmt.Errorf("read status line: %w", err)
	}
	if !strings.Contains(statusLine, "200") {
		return fmt.Errorf("refused: %s", strings.TrimSpace(statusLine))
	}
	for {
		line, err := r.ReadString('\n')
		if err != nil || line == "\r\n" || line == "\n" {
			return nil
		}
	}
}

// substrate returns a live client, dialling one if none exists yet or the last one died.
func (lp *localProxy) substrate(ctx context.Context, logf func(format string, args ...any)) (*multipath.Client, error) {
	lp.mu.Lock()
	defer lp.mu.Unlock()
	if lp.client != nil {
		return lp.client, nil
	}
	lines, err := fetchLines(ctx, lp.apiBase)
	if err != nil {
		logf("ccproxy: local proxy: line registry unavailable, falling back to same-origin: %v", err)
		lines = sameOriginLines(lp.apiBase)
	}
	logf("ccproxy: local proxy: dialling substrate over %d line(s), apiBase=%s", len(lines), lp.apiBase)
	dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	client, err := multipath.Dial(dialCtx, multipath.ClientOptions{Lines: lines})
	if err != nil {
		return nil, err
	}
	logf("ccproxy: local proxy: substrate up")
	lp.client = client
	return client, nil
}

func (lp *localProxy) dropSubstrate(dead *multipath.Client) {
	lp.mu.Lock()
	defer lp.mu.Unlock()
	if lp.client == dead {
		lp.client = nil
	}
	_ = dead.Close()
}

// fetchLines gets the operator-maintained line registry from the backend and resolves same-origin
// ("") entries against apiBase — the registry itself only ever carries bare origins or "", per
// multipath.Line's contract; resolving "" to a concrete URL is the caller's job (see
// multipath/go/link.go's resolveTransport).
func fetchLines(ctx context.Context, apiBase string) ([]multipath.Line, error) {
	reqCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, strings.TrimRight(apiBase, "/")+"/lines", nil)
	if err != nil {
		return nil, err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET /lines: status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	registry, err := multipath.ParseRegistry(body)
	if err != nil {
		return nil, err
	}
	lines := make([]multipath.Line, len(registry.Lines))
	for i, l := range registry.Lines {
		if l.URL == "" {
			l.URL = strings.TrimRight(apiBase, "/")
		}
		lines[i] = l
	}
	return lines, nil
}

func sameOriginLines(apiBase string) []multipath.Line {
	transport := "ws"
	if strings.HasPrefix(apiBase, "https://") {
		transport = "wss"
	}
	return []multipath.Line{{ID: "origin", URL: strings.TrimRight(apiBase, "/"), Transport: transport, Weight: 100}}
}

// splice pumps both directions until either side closes, mirroring ProxyServer.tunnel's shape.
func splice(a io.ReadWriteCloser, b io.ReadWriteCloser) (aToB, bToA int64) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		n, _ := io.Copy(b, a)
		aToB = n
		// One direction going EOF does not mean the other has nothing left to say (e.g. a server
		// still streaming a response after the client half-closed) — but neither side here
		// reliably supports a half-close (multipath.MuxStream does not), so close both once
		// either leg ends rather than leaking the other goroutine blocked forever on a peer that
		// will never send more.
		_ = a.Close()
		_ = b.Close()
	}()
	go func() {
		defer wg.Done()
		n, _ := io.Copy(a, b)
		bToA = n
		_ = a.Close()
		_ = b.Close()
	}()
	wg.Wait()
	return
}
