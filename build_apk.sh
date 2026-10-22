#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

: "${CLOCK_KEYSTORE_FILE:?Set the path to your existing release keystore}"
: "${CLOCK_KEYSTORE_PASSWORD:?Set the keystore password}"
: "${CLOCK_KEY_ALIAS:?Set the existing signing key alias}"
: "${CLOCK_KEY_PASSWORD:?Set the signing key password}"

# Reuse the established signing identity so installed applications remain upgradeable.
# Key generation and credential storage must stay outside the build process.
./gradlew :app:assembleRelease "$@"
printf 'Signed APK: app/build/outputs/apk/release/app-release.apk\n'
