"""Install an instance-owned same-host T4 bridge from accepted executor templates."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid

FILES = ("managed_local_setup.py", "managed_source_setup.py", "managed_origin_extension.py",
         "managed_runner_tunnel.py", "managed_broker_service.py", "managed_loader.py",
         "source_egress.py", "source_origin_discovery.py")


def install(root, templates, unit=None, uid=65532):
    if os.geteuid() != 0:
        raise ValueError("LOCAL_T4_ROOT_REQUIRED")
    root = Path(root)
    if (not root.is_absolute() or root.resolve() != root or not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(root))
            or len(str(root / "api/w-123456789abc/executor.sock").encode()) > 100
            or not isinstance(templates, list) or not 0 < len(templates) <= 16):
        raise ValueError("LOCAL_T4_INSTALLATION_INVALID")
    if unit and (not re.fullmatch(r"at-maintenance-t4-[a-z0-9-]{1,40}", unit)
                 or Path("/etc/systemd/system", unit + ".service").exists()):
        raise ValueError("LOCAL_T4_SERVICE_ALREADY_EXISTS")
    for template in templates:
        if (not re.fullmatch(r"sha256:[a-f0-9]{64}", template["imageId"])
                or not re.fullmatch(r"[a-f0-9]{64}", template["bundleSha256"])
                or not Path(template["runtimeRoot"]).is_dir()):
            raise ValueError("LOCAL_T4_TEMPLATE_INVALID")
    root.mkdir(mode=0o711)
    root.chmod(0o711)
    scripts = root / "manager"
    scripts.mkdir(mode=0o755)
    scripts.chmod(0o755)
    for name in FILES:
        shutil.copyfile(Path(__file__).with_name(name), scripts / name)
        (scripts / name).chmod(0o644)
    channels = []
    for index, template in enumerate(templates):
        directory = root / ("template-" + str(index))
        directory.mkdir(mode=0o711)
        directory.chmod(0o711)
        (directory / "broker-service.json").write_text(json.dumps({**template, "root": str(directory)}), encoding="utf-8")
        channels.append({"id": "template-" + str(index), "localPort": 19004 + index,
                         "remoteSocket": str(directory / "executor.sock"), "instanceId": str(uuid.uuid4()),
                         "imageId": template["imageId"], "bundleSha256": template["bundleSha256"],
                         "egressPolicySha256": "", "httpImageId": template["httpImageId"] or ""})
    (root / "channels.json").write_text(json.dumps({"schemaVersion": 1, "runners": channels}), encoding="utf-8")
    (root / "routes.json").write_text('{"schemaVersion":1,"runners":[]}', encoding="utf-8")
    api = root / "api"
    api.mkdir(mode=0o755)
    api.chmod(0o755)
    (api / "routes.json").write_text('{"schemaVersion":1,"runners":[]}', encoding="utf-8")
    (api / "routes.json").chmod(0o644)
    if unit:
        text = ("[Unit]\nDescription=AT maintenance isolated T4 bridge\nAfter=docker.service\nRequires=docker.service\n"
                "[Service]\nType=simple\nUMask=0077\n"
                "ExecStart=/usr/bin/python3 -I " + str(scripts / "managed_local_setup.py")
                + " --root " + str(root) + " --uid " + str(uid) + "\n"
                "Restart=on-failure\nRestartSec=3\nKillMode=process\n"
                "[Install]\nWantedBy=multi-user.target\n")
        path = Path("/etc/systemd/system", unit + ".service")
        with path.open("x", encoding="utf-8") as stream:
            stream.write(text)
        path.chmod(0o644)
        subprocess.run(["systemctl", "daemon-reload"], check=True, timeout=30)
        subprocess.run(["systemctl", "enable", "--now", unit + ".service"], check=True, timeout=30)
    return {"installed": True, "ipcRoot": str(api), "unit": unit, "templates": len(channels),
            "workbenchDockerSocket": False, "credentialsCopied": False, "sourceExecuted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--templates-file", type=Path, required=True)
    parser.add_argument("--unit")
    parser.add_argument("--uid", type=int, default=65532)
    args = parser.parse_args()
    os.umask(0o077)
    print(json.dumps(install(args.root, json.loads(args.templates_file.read_bytes()), args.unit, args.uid)))


if __name__ == "__main__":
    main()
