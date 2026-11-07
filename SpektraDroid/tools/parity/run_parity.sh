#!/usr/bin/env bash
# run_parity.sh -- engine vs spektrafilm reference, tap by tap.
#
#   tools/parity/run_parity.sh <input.npy> [key=value ...]
#
# input.npy: float32 HxWx3 *linear* sRGB (what the app hands the engine).
# Keys: spk::RenderParams names, e.g. film_stock=kodak_vision3_500t
#       print_stock=kodak_2383 grain_active=0 glare_active=0 exposure_ev=1
# Grain and glare are random: compare with them off for exact parity, or
# compare statistics with them on.
#
# Environment:
#   SPEKTRAFILM_SRC   spektrafilm's src/ dir (default: <root>/upstream/spektrafilm/src)
#   PYTHON            python 3.13 with spektrafilm's deps (default: python3)
#   CXX               C++20 compiler (default: g++; MSYS2 g++ works on Windows)
#   OUT               output dir (default: <android project>/build/parity)
set -euo pipefail
TOOLS="$(cd "$(dirname "$0")/.." && pwd)"
if [ -d "$TOOLS/../app" ]; then DROID="$(cd "$TOOLS/.." && pwd)"; else DROID="$(cd "$TOOLS/../SpektraDroid" && pwd)"; fi
ROOT="$(cd "$DROID/.." && pwd)"
IN="$1"; shift
OUT="${OUT:-$DROID/build/parity}"
CPP="$DROID/app/src/main/cpp"
PY="${PYTHON:-python3}"
SRC="${SPEKTRAFILM_SRC:-$ROOT/upstream/spektrafilm/src}"
[ -d "$SRC/spektrafilm" ] || { echo "spektrafilm not found at $SRC (set SPEKTRAFILM_SRC)"; exit 2; }
mkdir -p "$OUT/ref" "$OUT/eng"

if [ ! -x "$OUT/host_parity" ] || [ "$CPP/spektra_engine.cpp" -nt "$OUT/host_parity" ] || [ "$CPP/overscan.cpp" -nt "$OUT/host_parity" ]; then
  ${CXX:-g++} -std=c++20 -O2 -pthread -I "$CPP" "$TOOLS/parity/host_parity.cpp" \
    "$CPP/spektra_engine.cpp" "$CPP/filters.cpp" "$CPP/cam16.cpp" "$CPP/overscan.cpp" -o "$OUT/host_parity"
fi

PYTHONPATH="$SRC" "$PY" "$TOOLS/parity/oracle.py" "$IN" "$OUT/ref" "$@"
"$OUT/host_parity" "$DROID/app/src/main/assets/engine/engine.bin" "$IN" "$OUT/eng" "$@"
"$PY" "$TOOLS/parity/compare.py" "$OUT/ref" "$OUT/eng" --png "$OUT/compare.png"
