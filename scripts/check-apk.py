#!/usr/bin/env python3
"""Enforce size and permissions against the packaged APK rather than a build intermediate."""

import os
from pathlib import Path
import shutil
import subprocess
import sys

PROHIBITED = {
    "android.permission.INTERNET",
    "android.permission.WAKE_LOCK",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
}


def main():
    apk = Path(sys.argv[1])
    size = apk.stat().st_size
    if size > 12 * 1024 * 1024:
        raise SystemExit(f"APK exceeds 12 MiB: {size} bytes")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    aapt = shutil.which("aapt2")
    if not aapt and sdk:
        candidate = Path(sdk) / "build-tools" / "36.0.0" / "aapt2"
        if candidate.is_file():
            aapt = str(candidate)
    if not aapt:
        raise SystemExit("Set ANDROID_HOME to an SDK with build-tools 36.0.0, or add aapt2 to PATH")
    permissions = subprocess.check_output([aapt, "dump", "permissions", str(apk)], text=True)
    present = [permission for permission in sorted(PROHIBITED) if "'" + permission + "'" in permissions]
    if present:
        raise SystemExit("Prohibited permissions: " + ", ".join(present))
    print(f"APK: {size} bytes; prohibited permissions absent.")


if __name__ == "__main__":
    main()
