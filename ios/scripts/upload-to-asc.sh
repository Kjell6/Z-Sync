#!/bin/zsh
set -euo pipefail
# Uploads the already-exported IPA to App Store Connect.
# Requires: the app record to exist (bundle id de.kjell.zencompanion).
# Credentials: ~/.appstoreconnect/credentials.env + AuthKey_*.p8

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IPA="$ROOT/ios/build/export/ZenCompanion.ipa"
source "$HOME/.appstoreconnect/credentials.env"

if [[ ! -f "$IPA" ]]; then
  echo "Missing IPA at $IPA — archive/export first." >&2
  exit 1
fi

echo "Uploading $IPA …"
xcrun altool --upload-app --type ios \
  --file "$IPA" \
  --apiKey "$ASC_KEY_ID" \
  --apiIssuer "$ASC_ISSUER_ID"
echo "Done. Processing on App Store Connect can take a few minutes."
