#!/usr/bin/env bash
# Phase 0 field baseline for Edge Vision on real hardware.
#
# The emulator has no camera, so every performance and reliability claim has to
# come from a physical arm64 device. This script captures the numbers the app now
# logs itself (frames per second, draw cost, inference latency, dropped frames)
# together with battery, thermal and memory snapshots.
#
# Usage:
#   bash tools/field-baseline.sh              # 120 second capture
#   bash tools/field-baseline.sh 300          # 5 minute capture
#   bash tools/field-baseline.sh 300 ./out    # custom output directory
#
# While it runs: set Edge Vision as the live wallpaper, keep the home screen
# visible, and walk around with the camera pointing forward for the capture
# window. Attach with `adb logcat -s CameraWallpaper:I` to watch it live.
set -euo pipefail

DURATION_SECONDS="${1:-120}"
OUT_DIR="${2:-artifacts/field-baseline}"
PACKAGE="com.edgevision.wallpaper"
TAG="CameraWallpaper"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is required (install platform-tools and put adb on PATH)." >&2
  exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "No device or emulator is attached. Connect the phone, enable USB debugging, run 'adb devices'." >&2
  exit 1
fi

if ! adb shell pm list packages | grep -q "^package:${PACKAGE}$"; then
  echo "${PACKAGE} is not installed on the device. Install the debug or release APK first." >&2
  exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="${OUT_DIR}/${STAMP}"
mkdir -p "${TARGET}"

echo "Capturing ${DURATION_SECONDS}s of edge-vision metrics into ${TARGET}"

{
  echo "captured_at=${STAMP}"
  echo "duration_seconds=${DURATION_SECONDS}"
  adb shell getprop ro.product.model
  adb shell getprop ro.build.version.release
  adb shell getprop ro.build.version.sdk
  adb shell getprop ro.product.cpu.abi
  adb shell dumpsys package "${PACKAGE}" | grep -E "versionName|versionCode" | head -2
} > "${TARGET}/device.txt" 2>&1

adb shell dumpsys battery > "${TARGET}/battery-before.txt" 2>&1
adb shell dumpsys thermalservice > "${TARGET}/thermal-before.txt" 2>&1
adb shell dumpsys meminfo "${PACKAGE}" > "${TARGET}/meminfo-before.txt" 2>&1

echo "Recording logcat for ${DURATION_SECONDS}s..."
# timeout exits 124 on a normal timed capture, which must not fail the script.
timeout "${DURATION_SECONDS}" adb logcat -v time -s "${TAG}:I" "CameraController:W" \
  "HazardInference:W" "AndroidRuntime:E" > "${TARGET}/logcat.txt" || \
  test $? -eq 124

adb shell dumpsys battery > "${TARGET}/battery-after.txt" 2>&1
adb shell dumpsys thermalservice > "${TARGET}/thermal-after.txt" 2>&1
adb shell dumpsys meminfo "${PACKAGE}" > "${TARGET}/meminfo-after.txt" 2>&1
adb logcat -d -b crash > "${TARGET}/crash-buffer.txt" 2>&1 || true

grep -E "fps=" "${TARGET}/logcat.txt" > "${TARGET}/metrics.txt" || true

echo
echo "Baseline captured in ${TARGET}:"
echo "  metrics.txt     window fps / draw cost / inference latency / dropped frames"
echo "  battery-*.txt   drain over the window"
echo "  thermal-*.txt   thermal status before and after"
echo "  meminfo-*.txt   PSS before and after"
echo "  crash-buffer.txt native and Java crashes seen by the device"
echo
echo "Share metrics.txt and crash-buffer.txt when reporting a lag or crash."
