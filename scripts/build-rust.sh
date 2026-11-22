#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
cd "$ROOT/rust"
cargo ndk -t arm64-v8a -t x86_64 --platform 26 -o "$ROOT/android/app/src/main/jniLibs" build --release --locked
