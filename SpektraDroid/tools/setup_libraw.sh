#!/bin/bash
# Android RAW dependency: snapshot202611 adds A7 V / A7R VI and Nikon HE/HE*.
# Fetch an immutable commit and verify the complete source tree before use.
set -euo pipefail
project="$(cd "$(dirname "$0")/.." && pwd)"
destination="${1:-$project/build/deps/LibRaw-snapshot202611}"
commit=0b87f5c907d7ee0e7641dd1b6a6c9140b9451922
tree=fb93233a46ec9a96fb17f2dbab160f5fa27a0ab6
if [[ ! -e "$destination" ]]; then
    mkdir -p "$(dirname "$destination")"
    git init -q "$destination"
    git -C "$destination" remote add origin https://github.com/LibRaw/LibRaw.git
    git -C "$destination" fetch -q --depth 1 origin "$commit"
    git -C "$destination" -c advice.detachedHead=false checkout -q --detach FETCH_HEAD
fi
[[ "$(git -C "$destination" rev-parse HEAD)" == "$commit" &&
   "$(git -C "$destination" rev-parse 'HEAD^{tree}')" == "$tree" &&
   -z "$(git -C "$destination" status --porcelain --ignored)" ]] || {
    echo "LibRaw source does not match the pinned, clean snapshot; existing files were preserved." >&2
    exit 1
}
echo "LibRaw snapshot202611 verified ($commit)" >&2
cd "$destination"
pwd
