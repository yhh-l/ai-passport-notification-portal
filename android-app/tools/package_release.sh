#!/usr/bin/env bash
set -euo pipefail

ANDROID_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ANDROID_ROOT"

if [[ ! -f release-signing.properties ]]; then
    echo "Missing android-app/release-signing.properties." >&2
    echo "Copy release-signing.properties.example and point it at the private release keystore." >&2
    exit 1
fi

VERSION_NAME=$(sed -n "s/^[[:space:]]*versionName '\([^']*\)'.*/\1/p" app/build.gradle | head -1)
if [[ -z "$VERSION_NAME" ]]; then
    echo "Could not read versionName from app/build.gradle" >&2
    exit 1
fi

./gradlew clean assembleRelease lintRelease

SOURCE_APK="app/build/outputs/apk/release/app-release.apk"
DEST_DIR="$ANDROID_ROOT/dist"
DEST_APK="$DEST_DIR/AI-Passport-Portal-Android-${VERSION_NAME}.apk"
mkdir -p "$DEST_DIR"
cp "$SOURCE_APK" "$DEST_APK"

SDK_ROOT=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
APKSIGNER=$(find "$SDK_ROOT/build-tools" -type f -name apksigner 2>/dev/null |
    grep -v -- '-rc' | sort -V | tail -1 || true)
if [[ -z "$APKSIGNER" ]]; then
    APKSIGNER=$(find "$SDK_ROOT/build-tools" -type f -name apksigner 2>/dev/null |
        sort -V | tail -1 || true)
fi
if [[ -z "$APKSIGNER" ]]; then
    echo "apksigner not found under $SDK_ROOT/build-tools; refusing to publish an unverified APK." >&2
    exit 1
fi
"$APKSIGNER" verify --verbose --print-certs "$DEST_APK"

ZIPALIGN="$(dirname "$APKSIGNER")/zipalign"
if [[ ! -x "$ZIPALIGN" ]]; then
    echo "zipalign not found next to $APKSIGNER; refusing to publish an unchecked APK." >&2
    exit 1
fi
"$ZIPALIGN" -c -P 16 4 "$DEST_APK"

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$DEST_DIR" && sha256sum "$(basename "$DEST_APK")" > SHA256SUMS.txt)
else
    (cd "$DEST_DIR" && shasum -a 256 "$(basename "$DEST_APK")" > SHA256SUMS.txt)
fi

printf '\nRelease package:\n  %s\nChecksum:\n  %s\n' "$DEST_APK" "$DEST_DIR/SHA256SUMS.txt"
