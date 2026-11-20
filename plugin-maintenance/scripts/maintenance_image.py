"""Named, labelled image builds and explicitly protected project-only retirement."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess

PROJECT = "at-plugin-maintenance"
LABEL = "org.alist-tvbox.maintenance."
ID = re.compile(r"sha256:[a-f0-9]{64}\Z")
CONTAINER_ID = re.compile(r"[a-f0-9]{64}\Z")
CONTAINER_FORMAT = ('{"Id":{{json .Id}},"Name":{{json .Name}},"Image":{{json .Image}},'
                    '"State":{{json .State}},"Mounts":{{json .Mounts}},'
                    '"Config":{"Labels":{{json .Config.Labels}}}}')


def identity(role, version, revision, build_id=None, retention="temporary"):
    version = version.removeprefix("v").lower()
    if (not re.fullmatch(r"[a-z][a-z0-9-]{0,39}", role)
            or not re.fullmatch(r"[0-9][a-z0-9.-]{0,39}", version)
            or not re.fullmatch(r"[a-f0-9]{7,64}", revision)
            or retention not in {"temporary", "release"}):
        raise ValueError("IMAGE_IDENTITY_INVALID")
    now = datetime.now(timezone.utc)
    build_id = build_id or now.strftime("%Y%m%dT%H%M%S%fZ").lower()
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,39}", build_id):
        raise ValueError("IMAGE_BUILD_ID_INVALID")
    return {"tag": f"{PROJECT}-{role}:v{version}-{build_id}-{revision[:12]}",
            "labels": {LABEL + "project": PROJECT, LABEL + "role": role,
                       LABEL + "version": version, LABEL + "revision": revision,
                       LABEL + "build": build_id, LABEL + "retention": retention,
                       "org.opencontainers.image.created": now.isoformat(),
                       "org.opencontainers.image.version": version,
                       "org.opencontainers.image.revision": revision}}


def build_arguments(meta, dockerfile, context, build_args=(), network=None):
    arguments = ["build", "--pull=false", "--file", str(dockerfile), "--tag", meta["tag"]]
    for key, value in sorted(meta["labels"].items()):
        arguments.extend(["--label", key + "=" + value])
    for value in build_args:
        arguments.extend(["--build-arg", value])
    if network:
        arguments.extend(["--network", network])
    return [*arguments, str(context)]


def docker(arguments):
    return subprocess.run(["docker", *arguments], check=True, capture_output=True,
                          text=True, encoding="utf-8", timeout=120).stdout


def keep_images(document):
    if (set(document) != {"current", "rollback", "runtime"}
            or not document["current"]
            or any(not isinstance(rows, list) for rows in document.values())
            or any(not isinstance(i, str) or not ID.fullmatch(i)
                   for rows in document.values() for i in rows)):
        raise ValueError("IMAGE_KEEP_MANIFEST_INVALID")
    return {i for rows in document.values() for i in rows}


def retirement_plan(images, role, protected):
    result = []
    for image in images:
        ident = image["Id"]
        labels = image.get("Config", {}).get("Labels") or {}
        tags = image.get("RepoTags") or []
        if (not ID.fullmatch(ident) or ident in protected
                or labels.get(LABEL + "project") != PROJECT
                or labels.get(LABEL + "role") != role
                or labels.get(LABEL + "retention") not in {"temporary", "release"}
                or any(not t.startswith(PROJECT + "-" + role + ":") for t in tags)):
            continue
        result.append({"imageId": ident, "tags": sorted(tags)})
    return result


def container_inventory():
    ids = docker(["container", "ls", "--all", "--quiet", "--no-trunc"]).split()
    if not ids:
        return []
    return [json.loads(line) for line in docker(
        ["container", "inspect", "--format", CONTAINER_FORMAT, *ids]).splitlines()]


def stopped_container_plan(containers, images, role, protected):
    eligible = {row["imageId"] for row in retirement_plan(images, role, protected)}
    result = []
    for container in containers:
        state = container.get("State") or {}
        labels = container.get("Config", {}).get("Labels") or {}
        # Embedded control-plane state must outlive retirement of its disposable process.
        data = [row["Source"] for row in container.get("Mounts", [])
                if row.get("Destination") == "/data" and row.get("Type") == "bind"
                and isinstance(row.get("Source"), str) and row["Source"].startswith("/")]
        if (role != "embedded" or not CONTAINER_ID.fullmatch(container.get("Id", ""))
                or container.get("Image") not in eligible
                or state.get("Status") not in {"exited", "created", "dead"}
                or state.get("Running") is not False or state.get("Restarting") is not False
                or labels.get(LABEL + "project") != PROJECT or labels.get(LABEL + "role") != role
                or len(data) != 1):
            continue
        result.append({"containerId": container["Id"], "name": container.get("Name", ""),
                       "imageId": container["Image"], "preservedDataPath": data[0]})
    return result


def retire(role, keep, apply=False, retire_stopped_containers=False):
    protected = keep_images(keep)
    containers = container_inventory()
    ids = sorted(set(docker(["image", "ls", "--all", "--quiet", "--no-trunc", "--filter",
                            "label=" + LABEL + "project=" + PROJECT, "--filter",
                            "label=" + LABEL + "role=" + role]).split()))
    images = json.loads(docker(["image", "inspect", *ids])) if ids else []
    container_plan, container_results = [], []
    if retire_stopped_containers:
        protected.update(row["Image"] for row in containers
                         if row.get("State", {}).get("Status") not in {"exited", "created", "dead"}
                         or row.get("State", {}).get("Running") is not False
                         or row.get("State", {}).get("Restarting") is not False)
        container_plan = stopped_container_plan(containers, images, role, protected)
        if apply:
            for row in container_plan:
                ident = row["containerId"]
                current = json.loads(docker(["container", "inspect", "--format", CONTAINER_FORMAT, ident]))
                if stopped_container_plan([current], images, role, protected) != [row]:
                    container_results.append({"containerId": ident, "status": "SKIPPED_CHANGED"})
                    continue
                try:
                    docker(["container", "rm", ident])
                    container_results.append({"containerId": ident, "status": "DELETED"})
                except subprocess.CalledProcessError as error:
                    container_results.append({"containerId": ident, "status": "RETAINED_DOCKER_REFUSED",
                                              "exitCode": error.returncode})
            containers = container_inventory()
        else:
            planned_ids = {row["containerId"] for row in container_plan}
            containers = [row for row in containers if row["Id"] not in planned_ids]
    protected.update(row["Image"] for row in containers)
    plan = retirement_plan(images, role, protected)
    results = []
    if apply:
        for row in plan:
            ident = row["imageId"]
            current = json.loads(docker(["image", "inspect", ident]))[0]
            if not retirement_plan([current], role, protected) or set(current.get("RepoTags") or []) != set(row["tags"]):
                results.append({"imageId": ident, "status": "SKIPPED_CHANGED"})
                continue
            try:
                # No force, ancestor pruning or volume deletion.
                for ref in row["tags"][:-1]:
                    if docker(["image", "inspect", "--format", "{{.Id}}", ref]).strip() != ident:
                        raise ValueError("IMAGE_TAG_CHANGED")
                    docker(["image", "rm", "--no-prune", ref])
                docker(["image", "rm", "--no-prune", ident])
                results.append({"imageId": ident, "status": "DELETED"})
            except subprocess.CalledProcessError as error:
                results.append({"imageId": ident, "status": "RETAINED_DOCKER_REFUSED", "exitCode": error.returncode})
    return {"project": PROJECT, "role": role, "apply": apply, "protectedImageIds": sorted(protected),
            "candidates": plan, "results": results, "forceDelete": False, "globalPrune": False,
            "retireStoppedContainers": retire_stopped_containers, "containerCandidates": container_plan,
            "containerResults": container_results, "volumesDeleted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    for mode in ("plan", "build"):
        item = sub.add_parser(mode)
        for name in ("role", "version", "revision"):
            item.add_argument("--" + name, required=True)
        item.add_argument("--build-id")
        item.add_argument("--retention", choices=("temporary", "release"), default="temporary")
        if mode == "build":
            item.add_argument("--dockerfile", required=True, type=Path)
            item.add_argument("--context", required=True, type=Path)
            item.add_argument("--build-arg", action="append", default=[])
            item.add_argument("--network")
            item.add_argument("--receipt", required=True, type=Path)
    item = sub.add_parser("retire")
    item.add_argument("--role", required=True)
    item.add_argument("--keep-file", required=True, type=Path)
    item.add_argument("--apply", action="store_true")
    item.add_argument("--retire-stopped-containers", action="store_true",
                      help="Also retire stopped embedded instances with persistent /data bind mounts")
    item.add_argument("--receipt", required=True, type=Path)
    args = parser.parse_args()
    if args.mode == "plan":
        print(json.dumps(identity(args.role, args.version, args.revision, args.build_id, args.retention), indent=2))
        return
    # Reserve a new receipt before any Docker mutation; never replay an old operation.
    with args.receipt.open("x", encoding="utf-8") as receipt:
        json.dump({"phase": "STARTED", "mode": args.mode}, receipt)
    if args.mode == "build":
        meta = identity(args.role, args.version, args.revision, args.build_id, args.retention)
        subprocess.run(["docker", *build_arguments(meta, args.dockerfile, args.context,
                                                  args.build_arg, args.network)], check=True)
        result = {**meta, "imageId": docker(["image", "inspect", "--format", "{{.Id}}", meta["tag"]]).strip(),
                  "phase": "BUILT_NOT_ACCEPTED", "cleanupRequiredAfterAcceptance": True}
    else:
        result = retire(args.role, json.loads(args.keep_file.read_bytes()), args.apply,
                        args.retire_stopped_containers)
    args.receipt.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
