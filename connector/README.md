# ccproxy-connector

The machine-side agent. It lets the ccproxy control plane run the Claude Code login screen (and, in
time, provisioning commands) on a machine it **cannot SSH into**: the machine dials out over the
shared [micro-connector](https://github.com/micro-teams/micro-connector) transport, and nothing
dials in.

It is deliberately tiny. Everything hard — driving the terminal, hosting the login applet, keeping a
screen alive across a self-update, enrolment, the dial-out transport — lives in `micro-connector/cli`
and is maintained once for the three products that share it (MicroTeams, ccproxy, cheese). All that
is here is the **brand** (`brand.go` — the names that make this ccproxy) and a **command tree**
(`main.go`) wiring the library's pieces together. If it grows a second copy of the library's logic,
the fork we deleted is coming back.

## Commands

| command | what it does |
|---|---|
| `enroll [base]` | run the device flow against the control plane and store this machine's identity |
| `connect [base]` | enrol if needed, then install + start the boot service so it reconnects on boot |
| `run` | the resident host the service launches (dial out, serve screens); hidden, foreground |
| `disconnect` | stop and remove the boot service (identity kept — `connect` brings it back) |
| `status` | enrolment + service status |
| `update` | replace the binary with the one the control plane publishes, then restart the service |
| `uninstall` | remove the service and this machine's stored identity |

`base` is the control plane's public origin, including any gateway prefix, e.g.
`https://host/ccproxy`. A freshly-installed machine is told to run `connect <base>`.

## Build

```
go build ./...
```

The Go version is pinned by `go.mod` to match the shared library. CI cross-compiles the published
targets (linux/darwin × amd64/arm64); the control plane serves those binaries under
`/connector/latest/<os>-<arch>/ccproxy-connector`, which is what `install.sh` downloads.
