#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# Release builds remain unsigned without private signing configuration.
exec ./gradlew :app:assembleRelease "$@"
