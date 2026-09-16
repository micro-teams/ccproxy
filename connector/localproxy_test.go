package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	multipath "github.com/micro-teams/multipath/go"
)

func TestAnthropicDomainsMatchesDataplaneMitmDomains(t *testing.T) {
	// Kept in sync by hand with backend's CCProxyConfig.Dataplane.mitmDomains default — this test
	// exists so a change to one without the other fails loudly instead of quietly un-splitting
	// traffic that used to be MITM'd (or MITM'ing traffic that no longer should be).
	want := map[string]bool{"api.anthropic.com": true, "platform.claude.com": true}
	if len(anthropicDomains) != len(want) {
		t.Fatalf("anthropicDomains = %v, want %v", anthropicDomains, want)
	}
	for k := range want {
		if !anthropicDomains[k] {
			t.Errorf("anthropicDomains missing %q", k)
		}
	}
}

// withHomeDir points credentialPath at dir for the duration of the test, without touching the real
// HOME env — production intentionally ignores HOME (it runs unset; see homeDir's doc comment).
func withHomeDir(t *testing.T, dir string) {
	t.Helper()
	orig := homeDir
	homeDir = func() string { return dir }
	t.Cleanup(func() { homeDir = orig })
}

func TestReadCredentialRoundTrips(t *testing.T) {
	dir := t.TempDir()
	withHomeDir(t, dir)
	credDir := filepath.Join(dir, ".config", "ccproxy-connector")
	if err := os.MkdirAll(credDir, 0o700); err != nil {
		t.Fatal(err)
	}
	data, _ := json.Marshal(proxyCredential{ProxyUser: "m123", ProxyPassword: "s3cret"})
	if err := os.WriteFile(filepath.Join(credDir, "proxy-credential.json"), data, 0o600); err != nil {
		t.Fatal(err)
	}
	cred, err := readCredential()
	if err != nil {
		t.Fatal(err)
	}
	if cred.ProxyUser != "m123" || cred.ProxyPassword != "s3cret" {
		t.Fatalf("got %+v", cred)
	}
}

func TestReadCredentialMissingFile(t *testing.T) {
	withHomeDir(t, t.TempDir())
	if _, err := readCredential(); err == nil {
		t.Fatal("expected an error for a missing credential file")
	}
}

// TestCredentialPathIgnoresHOMEEnv guards the actual CI failure this was debugging: the connector
// runs with HOME unset, so os.UserHomeDir() (which just reads $HOME) silently produced a RELATIVE
// path (".config/ccproxy-connector/proxy-credential.json") that never matched where the backend
// wrote the file. credentialPath must resolve via the passwd database, not the environment.
func TestCredentialPathIgnoresHOMEEnv(t *testing.T) {
	t.Setenv("HOME", "")
	if !filepath.IsAbs(credentialPath()) {
		t.Fatalf("credentialPath() = %q, want an absolute path even with HOME unset", credentialPath())
	}
}

// TestReadOriginConnectResponse guards the actual CI failure this was debugging: ProxyServer, on
// the other end of the substrate, speaks the same CONNECT protocol the local client does — it
// replies with its own "HTTP/1.1 200 Connection Established\r\n\r\n" before any TLS bytes. Splicing
// that literal text straight through as if it were TLS data broke the handshake instantly (visible
// in prod/CI as ~39 bytes back from origin and an immediate stream close). This must be read and
// consumed, not forwarded.
func TestReadOriginConnectResponse(t *testing.T) {
	t.Run("200 is consumed, leaving TLS bytes untouched", func(t *testing.T) {
		r := bufio.NewReader(strings.NewReader("HTTP/1.1 200 Connection Established\r\n\r\n\x16\x03\x01tls-bytes-follow"))
		if err := readOriginConnectResponse(r); err != nil {
			t.Fatal(err)
		}
		rest, _ := io.ReadAll(r)
		if string(rest) != "\x16\x03\x01tls-bytes-follow" {
			t.Fatalf("got %q, want the TLS bytes untouched by the status-line read", rest)
		}
	})

	t.Run("non-200 is an error, not silently spliced through", func(t *testing.T) {
		r := bufio.NewReader(strings.NewReader("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		if err := readOriginConnectResponse(r); err == nil {
			t.Fatal("expected an error for a non-200 origin response")
		}
	})

	t.Run("EOF before a status line is an error", func(t *testing.T) {
		r := bufio.NewReader(strings.NewReader(""))
		if err := readOriginConnectResponse(r); err == nil {
			t.Fatal("expected an error for an empty response")
		}
	})
}

func TestFetchLinesResolvesSameOriginAgainstApiBase(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/lines" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_ = json.NewEncoder(w).Encode(multipath.Registry{
			Lines: []multipath.Line{{ID: "origin", URL: "", Transport: "ws", Weight: 100}},
		})
	}))
	defer srv.Close()

	lines, err := fetchLines(context.Background(), srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if len(lines) != 1 || lines[0].URL != srv.URL {
		t.Fatalf("got %+v, want same-origin resolved to %q", lines, srv.URL)
	}
}

func TestFetchLinesFallsBackOnServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	if _, err := fetchLines(context.Background(), srv.URL); err == nil {
		t.Fatal("expected an error for a 500 response")
	}
	// The caller (localProxy.substrate) falls back to sameOriginLines on any fetchLines error —
	// exercised directly here since it needs no network.
	lines := sameOriginLines(srv.URL)
	if len(lines) != 1 || lines[0].URL != srv.URL || lines[0].Transport != "ws" {
		t.Fatalf("got %+v", lines)
	}
}

// TestHandleDirectSplicesToTheRealTarget proves the non-Anthropic path never involves the
// substrate: a plain CONNECT to a local test server round-trips bytes with nothing but a real TCP
// dial in between.
func TestHandleDirectSplicesToTheRealTarget(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("hello from target"))
	}))
	defer target.Close()
	targetHostPort := strings.TrimPrefix(target.URL, "http://")

	client, server := net.Pipe()
	done := make(chan struct{})
	go func() {
		handleDirect(server, targetHostPort, func(string, ...any) {})
		close(done)
	}()

	br := bufio.NewReader(client)
	line, err := br.ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(line, "HTTP/1.1 200") {
		t.Fatalf("got status line %q, want 200 Connection Established", line)
	}
	// drain the rest of the CONNECT response headers (just the blank line here)
	for {
		l, err := br.ReadString('\n')
		if err != nil || l == "\r\n" {
			break
		}
	}
	if _, err := client.Write([]byte("GET / HTTP/1.1\r\nHost: x\r\n\r\n")); err != nil {
		t.Fatal(err)
	}
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	if string(body) != "hello from target" {
		t.Fatalf("got body %q", body)
	}
	_ = client.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("handleDirect did not return after the client closed")
	}
}
