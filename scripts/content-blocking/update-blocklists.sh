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
    # A short answer that still starts like a list must not replace what ships;
    # this is the same floor the apps apply to a downloaded list. Only rules the
    # apps keep count: block rules (no `@@`) of the form `||domain^` with a
    # dotted domain, either without options or with only options that mean the
    # whole request. A domain counts once per scope, the way the apps store it:
    # in `anywhere`, or in `thirdParty` when it is limited to third-party
    # requests. Lines limited to some request types and page-element rules keep
    # nothing.
    rules="$(awk '
        {
            line = tolower($0)
            if (line !~ /^\|\|[a-z0-9._-]+\^/) next
            domain = substr(line, 3, index(line, "^") - 3)
            gsub(/^\.+|\.+$/, "", domain)
            if (domain == "" || index(domain, ".") == 0) next
            rest = substr(line, index(line, "^") + 1)
            scope = "anywhere"
            if (rest != "") {
                if (substr(rest, 1, 1) != "$") next
                count = split(substr(rest, 2), option, ",")
                for (i = 1; i <= count; i++) {
                    value = option[i]
                    gsub(/^[ \t]+|[ \t]+$/, "", value)
                    if (value == "third-party" || value == "3p") scope = "thirdParty"
                    else if (value != "all" && value != "important") next
                }
            }
            if (!seen[scope "|" domain]++) kept++
        }
        END { print kept + 0 }
    ' "$work/$list.min.txt")"
    [ "${rules:-0}" -ge 1000 ] || { echo "$list: only ${rules:-0} rules" >&2; exit 1; }
    for out in "$root/ios/ZenCompanion/Browser/ContentBlocking/$list.txt" \
               "$root/android/app/src/main/assets/content-blocking/$list.txt"; do
        mkdir -p "$(dirname "$out")"
        cp "$work/$list.min.txt" "$out"
        echo "wrote $out ($(wc -l < "$out") lines)"
    done
done
