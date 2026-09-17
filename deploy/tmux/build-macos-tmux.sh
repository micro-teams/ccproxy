#!/usr/bin/env bash
# Build a portable tmux for macOS and drop it in the connector serving layout, so install.sh can
# hand a Mac a self-contained tmux even when the machine has none of its own (its fallback before
# this was "copy the machine's own tmux, and if there is none, tell the person to install one" — see
# install.sh's comment on tmux resolution).
#
# "Portable", not "static" — build-static-tmux.sh's Linux approach (musl + `-static`, a fully
# self-contained ELF) has no Darwin equivalent: macOS's libc cannot be statically linked at all. What
# IS possible, and is what this does: link libevent statically (Homebrew's .a, so no
# /opt/homebrew/lib dependency ships in the binary) while leaving ncurses dynamic against the
# system's own copy — /usr/lib/libncurses.dylib is part of every real Mac's base OS, not something
# Homebrew or Xcode installs, so depending on it costs nothing a machine doesn't already have.
#
# Usage:
#   deploy/tmux/build-macos-tmux.sh [--arch amd64|arm64] [--out DIR] [--version 3.5a]
#
#   --arch     target arch (default: arm64, i.e. this runner's own — no cross-compiling: Homebrew's
#              libevent bottle is native-arch only, and this is meant to run on a real
#              macos-latest/macos-13 runner per arch anyway, mirroring build-tmux's Linux matrix).
#   --out      artifact root; the binary lands at <out>/darwin-<arch>/tmux (Go-style arch).
#   --version  tmux release to build (default: 3.5a)
set -euo pipefail

arch="arm64"
version="3.5a"
out=""

while [ $# -gt 0 ]; do
  case "$1" in
    --arch)    arch="$2"; shift 2 ;;
    --out)     out="$2"; shift 2 ;;
    --version) version="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

case "$arch" in
  amd64|arm64) ;;
  *) echo "unsupported arch: $arch (amd64|arm64)" >&2; exit 2 ;;
esac

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
if [ -z "$out" ]; then
  out="${CONNECTOR_DIST_DIR:-$repo_root/.connector-dist}"
fi
# Absolute: everything below cd's into a scratch build dir, and a relative dest_dir would then
# resolve against THAT directory instead of where this script was invoked from — the actual bug
# that made the first version of this script fail with "cp: dist/darwin-arm64/tmux: No such file
# or directory" the moment it ran for real.
mkdir -p "$out"
dest_dir="$(cd "$out" && pwd)/darwin-$arch"
mkdir -p "$dest_dir"

command -v brew >/dev/null 2>&1 || { echo "homebrew is required" >&2; exit 1; }
brew list libevent >/dev/null 2>&1 || brew install libevent >/dev/null

LIBEVENT_PREFIX="$(brew --prefix libevent)"
# Homebrew's static archive layout has varied by version — prefer the split libevent_core.a (what
# tmux actually links against, per its own -levent_core) if present, else fall back to the combined
# libevent.a.
if [ -f "$LIBEVENT_PREFIX/lib/libevent_core.a" ]; then
  LIBEVENT_STATIC_LIBS="$LIBEVENT_PREFIX/lib/libevent_core.a"
elif [ -f "$LIBEVENT_PREFIX/lib/libevent.a" ]; then
  LIBEVENT_STATIC_LIBS="$LIBEVENT_PREFIX/lib/libevent.a"
else
  echo "no static libevent .a found under $LIBEVENT_PREFIX/lib" >&2
  ls -la "$LIBEVENT_PREFIX/lib" >&2 || true
  exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
curl -fsSL -o tmux.tar.gz "https://github.com/tmux/tmux/releases/download/${version}/tmux-${version}.tar.gz"
tar xzf tmux.tar.gz
cd "tmux-${version}"

# Point at libevent's headers; ncurses is left to configure's own defaults, resolving against the
# system's copy since nothing here points it anywhere else.
#
# Forcing the STATIC archive is the part that does not survive configure's own libevent detection
# — tmux's configure.ac does not honor a LIBEVENT_LIBS env override the way autoconf's
# PKG_CHECK_MODULES convention would suggest; it re-detects on its own and the final link ends up
# with plain `-levent_core` (dynamic) regardless of what gets exported here. So: leave configure to
# do whatever it wants, and instead put the .a file straight into LDFLAGS, which IS always honored
# verbatim on the final link line. The linker resolves libevent's symbols from that archive before
# it ever gets to the Makefile's own (now-redundant, harmless) `-levent_core`.
CPPFLAGS="-I$LIBEVENT_PREFIX/include" \
LDFLAGS="-L$LIBEVENT_PREFIX/lib $LIBEVENT_STATIC_LIBS" \
  ./configure --disable-utf8proc
make -j"$(sysctl -n hw.ncpu)"

cp tmux "$dest_dir/tmux"
chmod +x "$dest_dir/tmux"

# Prove it does not depend on a Homebrew libevent dylib that will not exist on the target machine.
if otool -L "$dest_dir/tmux" | grep -qi libevent; then
  echo "built tmux still links libevent dynamically — the static link did not take" >&2
  otool -L "$dest_dir/tmux" >&2
  exit 1
fi
echo "== built =="
ls -la "$dest_dir"
otool -L "$dest_dir/tmux"
