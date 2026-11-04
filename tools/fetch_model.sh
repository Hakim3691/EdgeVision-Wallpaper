#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="$ROOT_DIR/app/src/main/assets/models/hazard_efficientdet_lite0.tflite"
CHECKSUM_FILE="$ROOT_DIR/tools/model.sha256"

MODEL_URL="https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite"

expected="$(tr -d '[:space:]' < "$CHECKSUM_FILE")"
mkdir -p "$(dirname "$DESTINATION")"
echo "Downloading EfficientDet-Lite0..."
curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 \
    "$MODEL_URL" -o "$DESTINATION.tmp"
actual="$(sha256sum "$DESTINATION.tmp" | cut -d' ' -f1)"

if [[ "$actual" != "$expected" ]]; then
    rm -f "$DESTINATION.tmp"
    echo "Checksum mismatch: expected $expected, got $actual" >&2
    exit 1
fi

mv "$DESTINATION.tmp" "$DESTINATION"
printf 'Model installed: %s (%s bytes)\n' "$DESTINATION" "$(stat -c%s "$DESTINATION")"
