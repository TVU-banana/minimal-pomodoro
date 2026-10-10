#!/usr/bin/env python3
"""Package the reviewed SVGs and matching native assets without modifying the app."""

import json
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
DOWNLOADS = ROOT / "downloads"


def write_archive(target, files):
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(files):
            archive.write(file, file.relative_to(ROOT))
        archive.write(ROOT.parents[2] / "LICENSE", "LICENSE")
    with zipfile.ZipFile(target) as archive:
        assert archive.testzip() is None
    print(target.name, target.stat().st_size, "bytes")


def main():
    DOWNLOADS.mkdir(exist_ok=True)
    manifest = json.loads((ROOT / "manifest.json").read_text())
    for suite in manifest["suites"]:
        files = [p for p in (ROOT / suite["folder"]).rglob("*") if p.is_file()]
        files += [ROOT / "README.md", ROOT / "manifest.json"]
        write_archive(DOWNLOADS / f'clock-{suite["id"].lower()}-svg-android.zip', files)
    files = [p for p in ROOT.rglob("*")
             if p.is_file() and DOWNLOADS not in p.parents and "__pycache__" not in p.parts]
    files += [DOWNLOADS / f'clock-{suite["id"].lower()}-svg-android.zip'
              for suite in manifest["suites"]]
    write_archive(DOWNLOADS / "clock-logo-v2-all.zip", files)


if __name__ == "__main__":
    main()
