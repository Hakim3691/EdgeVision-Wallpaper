#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bash "$ROOT_DIR/tools/fetch_model.sh"
bash "$ROOT_DIR/tools/fetch_wrapper.sh"
