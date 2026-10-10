"""Install only the pinned AAR from Xiaomi's official interconnect development example."""
import hashlib
import io
from pathlib import Path
import urllib.request
import zipfile

URL = "https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip"
SHA256 = "9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195"
with urllib.request.urlopen(URL, timeout=30) as response:
    archive = response.read(20 * 1024 * 1024 + 1)
if len(archive) > 20 * 1024 * 1024:
    raise ValueError("Example archive exceeded the download budget")
with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
    sdk = bundle.read("interconnect_dev_test_demo/libs/xms-wearable-lib_1.4_release.aar")
if hashlib.sha256(sdk).hexdigest() != SHA256:
    raise ValueError("SDK changed; review the official archive before updating the pin")
output = Path(__file__).resolve().parents[1] / "app/libs/xms-wearable-lib_1.4_release.aar"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_bytes(sdk)
print(output)
