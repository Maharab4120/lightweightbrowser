#!/usr/bin/env bash
# Build the optional adblock-rust JNI library for Android.
# Run from any directory. One ABI at a time keeps memory use modest.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -f "$HOME/.cargo/env" ]; then
    # shellcheck disable=SC1091
    source "$HOME/.cargo/env"
fi

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "${SDK_DIR}" ] && [ -f local.properties ]; then
    SDK_DIR="$(grep '^sdk.dir=' local.properties | head -1 | cut -d= -f2-)"
fi
if [ -z "${SDK_DIR}" ]; then
    echo "Set ANDROID_SDK_ROOT or add sdk.dir to local.properties" >&2
    exit 1
fi

NDK_DIR="${ANDROID_NDK_HOME:-}"
if [ -z "${NDK_DIR}" ]; then
    NDK_DIR="$(find "$SDK_DIR/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "${NDK_DIR}" ]; then
    echo "Android NDK not installed; set ANDROID_NDK_HOME or install one under $SDK_DIR/ndk" >&2
    exit 1
fi

if ! command -v cargo-ndk >/dev/null 2>&1; then
    echo "cargo-ndk is required: cargo install cargo-ndk" >&2
    exit 1
fi

export ANDROID_NDK_HOME="$NDK_DIR"
export ANDROID_NDK_ROOT="$NDK_DIR"

OUT="$ROOT/app/src/main/jniLibs"
mkdir -p "$OUT"
cd "$ROOT/rust"

ABIS=(${*:-armeabi-v7a arm64-v8a x86 x86_64})
for ABI in "${ABIS[@]}"; do
    echo "Building adblock-rust JNI library for $ABI..."
    cargo ndk -t "$ABI" -P 24 -o "$OUT" build --release
done

echo "Native libraries in $OUT:"
find "$OUT" -type f -name '*.so' -printf '%P %k KB\n' | sort
