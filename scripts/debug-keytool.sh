#!/usr/bin/env bash
# Mirror of the Android CI workflow's local signing behavior.
#
# The workflow provisions a debug keystore with keytool when no repository secrets
# are configured, so a local developer can mirror that behavior with a throwaway
# key for a quick signed baseline:
#
#   bash scripts/debug-keytool.sh
#
# This is for demos and local troubleshooting only. It is not a store signing key.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYSTORE_DIR="$SCRIPT_DIR/../tmp/edgevision-keystore"
mkdir -p "$KEYSTORE_DIR"

keytool -genkeypair -noprompt \
  -keystore "$KEYSTORE_DIR/debug.keystore" \
  -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=AndroidClear Debug,O=AndroidClear,C=US"

echo "Wrote $KEYSTORE_DIR/debug.keystore"
