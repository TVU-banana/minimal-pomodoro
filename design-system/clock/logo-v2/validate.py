#!/usr/bin/env python3
"""Check SVG/native path parity and link the proposals with the app's theme colors."""

import json
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[2]
ANDROID = "http://schemas.android.com/apk/res/android"
SVG = "http://www.w3.org/2000/svg"


def luminance(color):
    channels = [int(color[i:i+2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
              for v in channels]
    return sum(a * b for a, b in zip(linear, [0.2126, 0.7152, 0.0722]))


def main():
    manifest = json.loads((ROOT / "manifest.json").read_text())
    sdk = Path.home() / "Library/Android/sdk"
    aapt = sdk / "build-tools/36.0.0/aapt2"
    jar = next(p for p in [sdk / "platforms/android-37/android.jar",
                          sdk / "platforms/android-36/android.jar"] if p.exists())
    report = {"svg_documents": 0, "native_xml_documents": 0, "suites": [], "contrast": []}
    for path in ROOT.rglob("*.svg"):
        root = ET.parse(path).getroot()
        for element in root.iter():
            assert element.tag.split("}")[-1] not in [
                "image", "script", "foreignObject", "filter"
            ], path
        report["svg_documents"] += 1
    for suite in manifest["suites"]:
        folder = ROOT / suite["folder"]
        paths_checked = 0
        for entry in suite["mapping"]:
            svg = ET.parse(folder / entry["svg"]).getroot()
            vector = ET.parse(folder / entry["resource"]).getroot()
            svg_paths = svg.findall(f"{{{SVG}}}path")
            native_paths = vector.findall("path")
            assert len(svg_paths) == len(native_paths), entry
            for a, b in zip(svg_paths, native_paths):
                assert a.get("d") == b.get(f"{{{ANDROID}}}pathData"), entry
                assert a.get("stroke-width") == b.get(f"{{{ANDROID}}}strokeWidth"), entry
                if a.get("fill-rule") == "evenodd":
                    assert b.get(f"{{{ANDROID}}}fillType") == "evenOdd", entry
                paths_checked += 1
        with tempfile.TemporaryDirectory(prefix="clock-icons-") as temporary:
            temporary = Path(temporary)
            res = temporary / "res"
            shutil.copytree(folder / "android-res", res)
            for qualifier in ["values", "values-night"]:
                (res / qualifier).mkdir()
                shutil.copy(PROJECT / "app/src/main/res" / qualifier / "colors.xml",
                            res / qualifier / "colors.xml")
            fixture = temporary / "AndroidManifest.xml"
            fixture.write_text(
                f'<manifest xmlns:android="{ANDROID}" package="com.akira.clock.iconreview">'
                '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37"/>'
                '<application/></manifest>'
            )
            compiled = temporary / "compiled.zip"
            subprocess.run([str(aapt), "compile", "--dir", str(res), "-o", str(compiled)],
                           check=True, capture_output=True)
            linked = subprocess.run([
                str(aapt), "link", "-I", str(jar), "--manifest", str(fixture),
                "--no-version-vectors", "-o", str(temporary / "review.apk"), str(compiled),
            ], capture_output=True, text=True)
            if linked.returncode:
                raise RuntimeError(linked.stderr)
        count = len(list((folder / "android-res").rglob("*.xml")))
        report["native_xml_documents"] += count
        report["suites"].append({"id": suite["id"], "resource_compile": "passed",
                                "resource_link": "passed", "path_parity": paths_checked,
                                "native_xml_count": count})
        print(f'{suite["id"]}: SVG/native geometry matches; Android resources compiled and linked.')
    for qualifier, theme in [("values", "light"), ("values-night", "dark")]:
        colors = {e.get("name"): e.text for e in ET.parse(
            PROJECT / "app/src/main/res" / qualifier / "colors.xml").getroot()}
        for role in ["clock_on_surface", "clock_on_surface_muted", "clock_primary"]:
            fg, bg = colors[role], colors["clock_background"]
            a, b = sorted([luminance(fg), luminance(bg)])
            contrast = (b + 0.05) / (a + 0.05)
            assert contrast >= 3, (theme, role, contrast)
            report["contrast"].append({"theme": theme, "role": role,
                                       "background": bg, "foreground": fg,
                                       "ratio": round(contrast, 2)})
    (ROOT / "qa").mkdir(exist_ok=True)
    (ROOT / "qa/resource-checks.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    print(f'{report["svg_documents"]} SVGs parsed; {report["native_xml_documents"]} native XMLs verified.')
    print('Theme contrast:', ', '.join(str(p["ratio"]) for p in report["contrast"]))


if __name__ == "__main__":
    main()
