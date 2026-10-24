#!/usr/bin/env bash
# Builds the cookie banner script from DuckDuckGo's autoconsent (MPL-2.0) and
# copies it into both apps. Run it to move to a new autoconsent release:
#   scripts/content-blocking/build-autoconsent.sh 16.61.0
set -euo pipefail
version="${1:?usage: build-autoconsent.sh <autoconsent version>}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cp "$here/autoconsent-entry.js" "$work/entry.js"
cd "$work"
npm init -y >/dev/null
npm install --silent --no-audit --no-fund "@duckduckgo/autoconsent@$version" esbuild@0.25 >/dev/null
npx esbuild entry.js --bundle --format=iife --target=es2019 --minify --legal-comments=none --outfile=bundle.js >/dev/null

header="/* DuckDuckGo autoconsent $version, MPL-2.0: https://github.com/duckduckgo/autoconsent
   Source of this build: scripts/content-blocking in the Z-Sync repository. */"
for out in "$root/ios/ZenCompanion/Browser/ContentBlocking/autoconsent.js" \
           "$root/android/app/src/main/assets/content-blocking/autoconsent.js"; do
    mkdir -p "$(dirname "$out")"
    { echo "$header"; cat bundle.js; } > "$out"
    echo "wrote $out"
done
