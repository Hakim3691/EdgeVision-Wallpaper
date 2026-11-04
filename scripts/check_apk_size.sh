#!/usr/bin/env bash
set -euo pipefail

MAX_BYTES=$((40 * 1024 * 1024))

if [[ $# -eq 0 ]]; then
    echo "usage: $0 <apk> [<apk> ...]" >&2
    exit 2
fi

status=0
for apk in "$@"; do
    if [[ ! -f "$apk" ]]; then
        echo "::error file=$apk::APK is missing"
        status=1
        continue
    fi

    size="$(stat -c%s "$apk")"
    human="$(awk -v bytes="$size" 'BEGIN { printf "%.2f", bytes / 1048576 }')"
    echo "$apk: ${size} bytes (${human} MiB)"

    if (( size > MAX_BYTES )); then
        echo "::error file=$apk::APK exceeds the 40 MiB (${MAX_BYTES} byte) size cap"
        status=1
    fi

    abis="$(unzip -Z1 "$apk" 'lib/*' 2>/dev/null | cut -d/ -f2 | sort -u | tr '\n' ' ' || true)"
    echo "  ABIs: ${abis:-none}"
done

exit "$status"
