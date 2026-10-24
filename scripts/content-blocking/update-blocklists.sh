#!/usr/bin/env bash
# Refreshes the ad and tracker lists that ship inside both apps. The apps fetch
# fresh copies from easylist.to once a week; these copies cover the first launch
# and devices that stay offline. Only domain rules are kept, because the apps
# ignore everything else.
#   scripts/content-blocking/update-blocklists.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for list in easylist easyprivacy; do
    curl -fsS --max-time 60 -o "$work/$list.txt" "https://easylist.to/easylist/$list.txt"
    head -1 "$work/$list.txt" | grep -q '^\[Adblock Plus' || { echo "$list: not a filter list" >&2; exit 1; }
    # The header comments (version, date, licence) and the domain rules.
    awk 'NR == 1 || /^!/ && NR < 40 || /^(@@)?\|\|[A-Za-z0-9._-]+\^/' "$work/$list.txt" > "$work/$list.min.txt"
    for out in "$root/ios/ZenCompanion/Browser/ContentBlocking/$list.txt" \
               "$root/android/app/src/main/assets/content-blocking/$list.txt"; do
        mkdir -p "$(dirname "$out")"
        cp "$work/$list.min.txt" "$out"
        echo "wrote $out ($(wc -l < "$out") lines)"
    done
done
