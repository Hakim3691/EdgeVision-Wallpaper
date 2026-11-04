#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar"
CHECKSUM_FILE="$ROOT_DIR/tools/wrapper.sha256"

WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"

expected="$(tr -d '[:space:]' < "$CHECKSUM_FILE")"

if [[ -f "$DESTINATION" ]]; then
    actual="$(sha256sum "$DESTINATION" | cut -d' ' -f1)"
    if [[ "$actual" == "$expected" ]]; then
        echo "Gradle wrapper already installed."
        exit 0
    fi
fi

mkdir -p "$(dirname "$DESTINATION")"
echo "Downloading Gradle 8.9 wrapper..."
curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 \
    "$WRAPPER_URL" -o "$DESTINATION.tmp"
actual="$(sha256sum "$DESTINATION.tmp" | cut -d' ' -f1)"

if [[ "$actual" != "$expected" ]]; then
    rm -f "$DESTINATION.tmp"
    echo "Checksum mismatch: expected $expected, got $actual" >&2
    exit 1
fi

mv "$DESTINATION.tmp" "$DESTINATION"
printf 'Gradle wrapper installed: %s (%s bytes)\n' "$DESTINATION" "$(stat -c%s "$DESTINATION")"
