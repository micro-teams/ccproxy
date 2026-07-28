#!/usr/bin/env bash
#
# One place to read or set the CCProxy product version. The repo-root VERSION file is the single
# source of truth; this script propagates it into the artifacts that must ship it.
#
# Usage:
#   scripts/version.sh            # print the current version + verify all files agree
#   scripts/version.sh <X.Y.Z>    # set the product version everywhere
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION_FILE="$ROOT/VERSION"
POM="$ROOT/backend/pom.xml"
API="$ROOT/CCProxy-API.yml"
FRONTEND_PKG="$ROOT/frontend/package.json"
FRONTEND_LOCK="$ROOT/frontend/package-lock.json"

semver_re='^[0-9]+\.[0-9]+\.[0-9]+$'
read_current() { tr -d '[:space:]' < "$VERSION_FILE"; }

if [[ $# -eq 0 ]]; then
  cur="$(read_current)"
  echo "product version (VERSION): $cur"
  echo
  printf '  %-26s %s\n' "backend/pom.xml"       "$(perl -0777 -ne 'print "$1\n" if /<artifactId>backend<\/artifactId>\s*<version>([^<]+)<\/version>/' "$POM")"
  printf '  %-26s %s\n' "CCProxy-API.yml"        "$(perl -ne 'print "$1\n" if /^  version:\s*"?([^"\n]+)"?/' "$API")"
  if [[ -f "$FRONTEND_PKG" ]]; then
    printf '  %-26s %s\n' "frontend/package.json" "$(node -p "require('$FRONTEND_PKG').version")"
  fi
  if [[ -f "$FRONTEND_LOCK" ]]; then
    printf '  %-26s %s\n' "frontend/package-lock.json" "$(node -p "require('$FRONTEND_LOCK').version")"
  fi
  exit 0
fi

new="$1"
if [[ ! "$new" =~ $semver_re ]]; then
  echo "error: version must be X.Y.Z (semver), got: $new" >&2
  exit 1
fi
api_ver="${new%.*}"   # X.Y for the OpenAPI info.version

echo "setting product version -> $new"
printf '%s\n' "$new" > "$VERSION_FILE"
perl -0777 -i -pe "s{(<artifactId>backend</artifactId>\s*<version>)[^<]+(</version>)}{\${1}$new\${2}}" "$POM"
perl -i -pe "s{^(  version:\s*).*}{\${1}\"$api_ver\"}" "$API"
if [[ -f "$FRONTEND_PKG" ]]; then
  node -e "const f='$FRONTEND_PKG';const p=require(f);p.version='$new';require('fs').writeFileSync(f, JSON.stringify(p,null,2)+'\n')"
fi
# Keep the lockfile in sync too, or `npm ci` fails on the version mismatch. Only the root package's
# version fields change (the top-level "version" and packages[""].version); dependency entries that
# happen to share a version string are untouched.
if [[ -f "$FRONTEND_LOCK" ]]; then
  node -e "const f='$FRONTEND_LOCK';const p=require(f);p.version='$new';if(p.packages&&p.packages['']){p.packages[''].version='$new'}require('fs').writeFileSync(f, JSON.stringify(p,null,2)+'\n')"
fi

echo "done. verifying:"
exec "$0"
