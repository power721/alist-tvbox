"""Export a pinned, secret-free AT-embedded image context."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

from prepare_upstream_review import write_archive

MODULE = Path(__file__).resolve().parents[1]


def build(mode, jar, runtime_image, output):
    if mode != "embedded" or not re.fullmatch(r"[A-Za-z0-9/._:-]+@sha256:[a-f0-9]{64}", runtime_image):
        raise ValueError("SERVICE_CONTEXT_IDENTITY_INVALID")
    with zipfile.ZipFile(jar) as archive:
        archive.getinfo("BOOT-INF/classes/static/index.html")
        archive.getinfo("BOOT-INF/classes/db/maintenance/h2/V73__MaintenanceAutomation.sql")
        if any(name.startswith("BOOT-INF/classes/maintenance-ui/") for name in archive.namelist()):
            raise ValueError("SERVICE_CONTEXT_INDEPENDENT_UI_NOT_ALLOWED")
    files = {"Dockerfile": (MODULE / ("deployment/Dockerfile." + mode)).read_bytes(),
             "service_entry.py": (MODULE / "deployment/service_entry.py").read_bytes(),
             "application.jar": jar.read_bytes()}
    digest = lambda body: hashlib.sha256(body).hexdigest()
    manifest = {"mode": mode, "runtimeImage": runtime_image,
                "files": {name: {"sha256": digest(body), "bytes": len(body)} for name, body in files.items()}}
    identity = digest(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode())
    files["service-context.json"] = (json.dumps({**manifest, "contextSha256": identity}, indent=2) + "\n").encode()
    write_archive(output, files)
    return {"mode": mode, "contextSha256": identity, "archiveSha256": digest(output.read_bytes()),
            "jarSha256": digest(files["application.jar"]), "archive": str(output)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("embedded",), default="embedded")
    for name in ("jar", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--runtime-image", required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.mode, args.jar, args.runtime_image, args.output)))
