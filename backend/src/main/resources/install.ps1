# install.ps1 — the native-Windows one-line installer for the `ccproxy-connector`, served by
# the backend.
#
#   irm <origin>/ccproxy/install.ps1 | iex
#
# Downloads the prebuilt windows-amd64 `ccproxy-connector.exe` from the same origin it was
# fetched from, so a fresh machine needs nothing pre-installed. It carries NO secrets and NO
# business logic: it only drops a binary and records the server URL. Enrolment happens
# separately, with a device token issued when the machine was created, so piping this to a
# shell never grants access by itself.
#
# Different from install.sh in exactly the ways Windows is different, and no others:
#   - No tmux. Screens (the interactive `/login` driver) genuinely do not work on native
#     Windows — there is no pty/tmux backend for it (see terminal.ErrUnsupported in
#     micro-connector) — but everything else (enrolment, the local MITM-split proxy, the
#     control connection, and the non-interactive setup-token login path) works the same as
#     anywhere else and does not need one.
#   - No lingering. That is a systemd --user concept; it has no Windows equivalent because
#     `ccproxy-connector connect` on Windows always installs a SYSTEM-level service (Windows'
#     Service Manager has no per-user service, unlike systemd --user or a launchd
#     LaunchAgent) — which already starts at boot with nobody logged in, the exact thing
#     lingering exists to get on Linux. That does mean `connect` needs an elevated
#     ("Run as Administrator") PowerShell; this installer itself does not.
#
# The backend bakes three values when it serves this file:
#   __CONNECTOR_BASE__ — the base the binaries hang off (<origin>/ccproxy; the artifacts
#                        live at <base>/connector/latest/<target>/…);
#   __API_BASE__       — the request/response + control API base the connector dials
#                        (<origin>/ccproxy);
#   __WS_BASE__        — an optional control-channel override (usually empty).
# Override any of them with $env:CCPROXY_CONNECTOR_BASE / CCPROXY_API_BASE / CCPROXY_WS_BASE.

$ErrorActionPreference = "Stop"

$ConnectorBase = if ($env:CCPROXY_CONNECTOR_BASE) { $env:CCPROXY_CONNECTOR_BASE } else { "__CONNECTOR_BASE__" }
$ApiBase = if ($env:CCPROXY_API_BASE) { $env:CCPROXY_API_BASE } else { "__API_BASE__" }
$WsOrigin = if ($env:CCPROXY_WS_BASE) { $env:CCPROXY_WS_BASE } else { "__WS_BASE__" }

$BinName = "ccproxy-connector.exe"

function Step($msg) { Write-Host "▸ $msg" -ForegroundColor Magenta }
function Ok($msg) { Write-Host "  ✓ $msg" -ForegroundColor Green }
function Die($msg) { Write-Host "✗ $msg" -ForegroundColor Red -ErrorAction Continue; exit 1 }

Write-Host ""
Write-Host "ccproxy connector · installer (windows)" -ForegroundColor White
Write-Host ""

# --- install locations --------------------------------------------------------------
# Same tree as the Go library's own config resolution (os.UserConfigDir() + brand.ConfigDir):
# %AppData%\ccproxy-connector\. The binary lives alongside config.json rather than on some
# separate bin/ path — Windows has no PATH convention for user-installed tools as strong as
# Unix's ~/.local/bin, so co-locating keeps this simple and gives one directory to point at.
$CfgDir = Join-Path $env:APPDATA "ccproxy-connector"
$Cfg = Join-Path $CfgDir "config.json"
$BinPath = Join-Path $CfgDir $BinName
New-Item -ItemType Directory -Force -Path $CfgDir | Out-Null

# --- 1. the connector binary ----------------------------------------------------------
Step "Installing $BinName"
$Target = "windows-amd64"
$Url = "$ConnectorBase/connector/latest/$Target/$BinName"
try {
    Invoke-WebRequest -Uri $Url -OutFile $BinPath -UseBasicParsing
} catch {
    Die "could not download $Url`: $_"
}
Ok "$BinPath"

# --- 2. remember the server ------------------------------------------------------------
# Same two endpoints install.sh writes, same meaning: "base" for enrolment + the API, "ws" for
# an optional control-channel override. Preserves any existing config.json content instead of
# clobbering it (a machine that already enrolled keeps its token across a re-run of this).
$ws = ""
if ($WsOrigin) {
    if ($WsOrigin.StartsWith("https://")) { $ws = "wss://" + $WsOrigin.Substring(8) }
    elseif ($WsOrigin.StartsWith("http://")) { $ws = "ws://" + $WsOrigin.Substring(7) }
    else { $ws = $WsOrigin }
    $ws = $ws.TrimEnd("/") + "/machine/link"
}
$existing = @{}
if (Test-Path $Cfg) {
    try { $existing = Get-Content $Cfg -Raw | ConvertFrom-Json -AsHashtable } catch { $existing = @{} }
    if ($null -eq $existing) { $existing = @{} }
}
$existing["base"] = $ApiBase
if ($ws) { $existing["ws"] = $ws } else { $existing.Remove("ws") | Out-Null }
($existing | ConvertTo-Json -Depth 5) | Set-Content -Path $Cfg -Encoding utf8
Step "Server"
Ok "$ApiBase"
if ($ws) { Ok "control channel: $ws" }

# --- 3. what now? ------------------------------------------------------------------------
# `connect` installs a Windows service, and Windows has no unprivileged per-user service —
# unlike Unix, where the equivalent (systemd --user / launchd LaunchAgent) needs no elevation.
# So, unlike install.sh's closing line, this one must say so.
Write-Host ""
Write-Host "Installed. One more command to go online (from an elevated / Administrator" -ForegroundColor Green -NoNewline
Write-Host " PowerShell):" -ForegroundColor Green
Write-Host ""
Write-Host "    & `"$BinPath`" connect --token <device-token>" -ForegroundColor White
Write-Host ""
Write-Host "The device token is shown in the ccproxy console when the machine is created." -ForegroundColor DarkGray
Write-Host "Manage later:  & `"$BinPath`" status / disconnect / uninstall" -ForegroundColor DarkGray
Write-Host ""
