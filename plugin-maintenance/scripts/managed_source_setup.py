"""Prepare a source-specific NAS channel. Parse source as AST; never execute it."""
from __future__ import annotations

import argparse
import ast
import base64
from contextlib import contextmanager
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import socket
import sys
import time
from urllib.parse import urlsplit
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import managed_runner_tunnel as T
from source_egress import MAX_ROUTES, source_trees, source_session_token_groups
from source_origin_discovery import http_origin, source_origins as discover_source_origins
from managed_loader import Invalid, T4Call, digest

UUID = r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
SHA = r"[a-f0-9]{64}"


class Failure(Exception):
    pass


def require(value, code):
    if not value:
        raise Failure(code)


def source_origins(content):
    try:
        return discover_source_origins(content)
    except ValueError as error:
        raise Failure(str(error)) from None


def source_imports(content):
    try:
        trees = source_trees(content)
        imports = {alias.name.split(".", 1)[0] for tree in trees
                   for node in ast.walk(tree) if isinstance(node, ast.Import) for alias in node.names}
        imports.update(node.module.split(".", 1)[0] for tree in trees
                       for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)
                       and node.module and node.level == 0)
        return imports
    except (SyntaxError, ValueError):
        raise Failure("SOURCE_SETUP_COMPANION_SYNTAX_INVALID") from None


def configured_origins(request):
    if "sourceConfig" not in request and "sourceConfigSha256" not in request:
        return []
    config = request.get("sourceConfig")
    try:
        T4Call.validate_source_data(config, allow_empty=True)
    except Invalid:
        raise Failure("SOURCE_SETUP_SOURCE_CONFIG_INVALID") from None
    require(request.get("sourceConfigSha256") == digest(config), "SOURCE_SETUP_SOURCE_CONFIG_CHANGED")
    return list(dict.fromkeys(origin for value in config.values()
                              if (origin := http_origin(value)) is not None))


def image_capabilities(path):
    path = Path(path)
    if not path.exists():
        return None
    require(path.stat().st_size <= 65536, "SOURCE_SETUP_IMAGE_REGISTRY_INVALID")
    registry = T.decode(path.read_bytes())
    require(isinstance(registry, dict) and set(registry) == {"schemaVersion", "images"}
            and type(registry["schemaVersion"]) is int and registry["schemaVersion"] == 1
            and isinstance(registry["images"], dict) and 0 < len(registry["images"]) <= 32,
            "SOURCE_SETUP_IMAGE_REGISTRY_INVALID")
    images = registry["images"]
    for image, modules in images.items():
        require(re.fullmatch("sha256:" + SHA, image) and isinstance(modules, list)
                and len(modules) <= 512 and all(isinstance(name, str)
                    and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) for name in modules)
                and len(set(modules)) == len(modules), "SOURCE_SETUP_IMAGE_REGISTRY_INVALID")
    return {image: set(modules) for image, modules in images.items()}


def companion_origins(content, registry_path, image_id, *, imported_modules=None):
    """Read only explicitly imported, administrator-registered image companions."""
    registry_path = Path(registry_path)
    if not registry_path.exists():
        return [], []
    require(registry_path.stat().st_size <= 65536, "SOURCE_SETUP_COMPANION_REGISTRY_INVALID")
    registry = T.decode(registry_path.read_bytes())
    require(isinstance(registry, dict) and set(registry) == {"schemaVersion", "modules"}
            and type(registry["schemaVersion"]) is int and registry["schemaVersion"] == 1
            and isinstance(registry["modules"], dict) and len(registry["modules"]) <= 32,
            "SOURCE_SETUP_COMPANION_REGISTRY_INVALID")
    modules, pending, visited, origins, receipts = registry["modules"], [content], set(), [], []
    while pending:
        current = pending.pop(0)
        imports = source_imports(current)
        if imported_modules is not None:
            imported_modules.update(imports)
        for name in sorted(imports & modules.keys() - visited):
            require(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name),
                    "SOURCE_SETUP_COMPANION_REGISTRY_INVALID")
            definition = modules[name]
            require(isinstance(definition, dict) and set(definition) == {"path", "sha256", "imageIds"}
                    and isinstance(definition["path"], str)
                    and isinstance(definition["sha256"], str) and re.fullmatch(SHA, definition["sha256"])
                    and isinstance(definition["imageIds"], list) and image_id in definition["imageIds"],
                    "SOURCE_SETUP_COMPANION_IMAGE_MISMATCH")
            path = Path(definition["path"])
            if not path.is_absolute():
                path = registry_path.parent / path
            require(path.name == name + ".py" and path.is_file() and path.stat().st_size <= 2097152,
                    "SOURCE_SETUP_COMPANION_FILE_INVALID")
            body = path.read_bytes()
            require(hashlib.sha256(body).hexdigest() == definition["sha256"],
                    "SOURCE_SETUP_COMPANION_CHANGED")
            try:
                discovered = discover_source_origins(body)
            except ValueError as error:
                require(str(error) == "SOURCE_SETUP_ORIGINS_REQUIRED", str(error))
                discovered = []
            visited.add(name)
            pending.append(body)
            origins.extend(origin for origin in discovered if origin not in origins)
            receipts.append({"module": name, "sha256": definition["sha256"],
                             "imageId": image_id, "origins": discovered})
    require(len(origins) <= MAX_ROUTES, "SOURCE_SETUP_ORIGIN_LIMIT")
    return origins, receipts


def atomic_json(path, value, expected=None):
    path = Path(path)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".pending")
    try:
        with temporary.open("xb") as stream:
            stream.write(json.dumps(value, ensure_ascii=True, indent=2).encode() + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        require(expected is None or path.read_bytes() == expected, "SOURCE_SETUP_CONFIGURATION_CHANGED")
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


@contextmanager
def setup_lock(directory):
    with (directory / "source-setup.lock").open("a+b") as stream:
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise Failure("SOURCE_SETUP_BUSY") from None
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


REMOTE = r'''
import base64,hashlib,json,os,pathlib,re,socket,subprocess,sys,urllib.parse,urllib.request
v=json.loads(sys.stdin.buffer.read(262145))
def check(value):
 if not value: raise RuntimeError("SOURCE_SETUP_REMOTE_FAILED")
def sha(body):return hashlib.sha256(body).hexdigest()
def put(path,body):
 if path.exists():check(path.is_file() and not path.is_symlink() and path.read_bytes()==body)
 else:
  with path.open("xb") as f:f.write(body);f.flush();os.fsync(f.fileno())
  path.chmod(0o600)
template=pathlib.Path(v["templateSocket"]).parent
check(template.resolve()==template and template.is_dir() and template.stat().st_uid==0)
config=json.loads((template/"broker-service.json").read_bytes())
check(not v.get("companionModules") or config["imageId"]==v["companionModules"][0]["imageId"])
check(not v.get("imageImports") or config["imageId"]==v["imageImports"]["imageId"])
runtime=pathlib.Path(config["runtimeRoot"])
check(runtime.resolve()==runtime and config["bundleSha256"]==v["bundleSha256"])
sys.path[:0]=[str(runtime/"scripts"),str(runtime/"vendor")]
import source_egress as E,build_managed_runtime as B
check(B.OUTPUT.read_bytes()==B.body() and sha(B.body())==v["bundleSha256"])
root=template.parent/("w-auto-"+v["instanceId"].replace("-","")[:12])
check(root.resolve()==root and root.parent==template.parent)
if not root.exists():root.mkdir(mode=0o711)
check(root.is_dir() and not root.is_symlink() and root.stat().st_uid==0)
root.chmod(0o711)
identity={k:v[k] for k in ("sourceId","sourceSha256","bundleSha256","instanceId","origins")}
if "sourceConfigSha256" in v:identity["sourceConfigSha256"]=v["sourceConfigSha256"]
put(root/"source-setup.json",json.dumps(identity,sort_keys=True,separators=(",",":")).encode())
policy_path=root/"egress-policy.json"
unresolved=[]
if not policy_path.exists():
 routes=[];pending=[];dns_codes=set()
 for origin in v["origins"]:
  host=urllib.parse.urlsplit(origin).hostname
  try:
   addresses=E.public_dns_addresses(host)
   route={"origin":origin,"exactUrls":[],"requestScope":"origin",**E.address_pins(addresses),
    "sessionToken":{"responseScope":"origin"}}
   headers=[item for item in v["origins"][:3] if item!=origin]
   if headers:route["headerOrigins"]=headers
   routes.append(route)
  except E.Denied as error:
   dns_codes.add(error.code)
   unresolved.append(origin)
   if error.code in {"EGRESS_DNS_NO_ADDRESS","EGRESS_DNS_FAILED","EGRESS_NONPUBLIC_DNS"}:pending.append(origin)
 if not routes:
  code=("SOURCE_SETUP_DNS_NO_ADDRESS" if dns_codes=={"EGRESS_DNS_NO_ADDRESS"} else
   "SOURCE_SETUP_DNS_NONPUBLIC" if dns_codes=={"EGRESS_NONPUBLIC_DNS"} else "SOURCE_SETUP_DNS_UNAVAILABLE")
  print(json.dumps({"ok":False,"code":code,"sourceExecuted":False,
   "bundleSha256":v["bundleSha256"],"instanceId":v["instanceId"]}))
  raise SystemExit(0)
 policy=E.policy_from({"schemaVersion":3,"methods":["GET","HEAD","POST"],"routes":routes,
  "pendingOrigins":pending,
  "sessionTokenGroups":[group for group in v.get("sessionTokenGroups",[])
   if all(origin in {r["origin"] for r in routes}|set(pending) for origin in group)],
  "responseRedirects":"public_http_https","responseApiOrigins":"public_http_https",
  "playerResources":"public_http_https",
  "maxRequests":64,"maxResponseBytes":2097152,
  "maxTotalResponseBytes":4194304,"minIntervalSeconds":1})
 put(policy_path,json.dumps(policy,ensure_ascii=True,separators=(",",":")).encode())
policy_body=policy_path.read_bytes()
policy=E.policy_from(json.loads(policy_body))
check(all(r.get("requestScope")=="origin" and r["origin"] in v["origins"] for r in policy["routes"]))
unresolved=[origin for origin in v["origins"] if origin not in {r["origin"] for r in policy["routes"]}]
journal=root/"journal"
if not journal.exists():journal.mkdir(mode=0o700)
check(journal.is_dir() and not journal.is_symlink() and journal.stat().st_uid==0)
journal.chmod(0o700)
service=base64.b64decode(v["serviceBase64"],validate=True)
check(sha(service)==v["serviceSha256"])
put(root/"managed_broker_service.py",service)
installed={"schemaVersion":1,"root":str(root),"runtimeRoot":str(runtime),
 "instanceId":v["instanceId"],"imageId":config["imageId"],"bundleSha256":v["bundleSha256"],
 "egressPolicyFile":str(policy_path),"egressPolicySha256":sha(policy_body),
 "httpImageId":config["httpImageId"] if v.get("useTemplateHttp") else None}
put(root/"broker-service.json",json.dumps(installed,sort_keys=True,separators=(",",":")).encode())
sys.path.insert(0,str(root))
import managed_broker_service as S
path,installed,config_sha=S.configuration(root/"broker-service.json")
if (root/"operator.sock").exists():
 observed=S.request(installed,config_sha)
else:
 with S.ExitStack() as stack:S.lock_file(stack,root/".service.lock")
 observed=S.start(path,installed,config_sha)
check(observed["state"]=="RUNNING" and not observed["identity"]["recoveryRequired"])
print(json.dumps({"ok":True,"remoteSocket":str(root/"executor.sock"),"instanceId":installed["instanceId"],
 "imageId":installed["imageId"],"bundleSha256":installed["bundleSha256"],
 "egressPolicySha256":installed["egressPolicySha256"],"httpImageId":installed["httpImageId"] or "",
 "approvedOrigins":[r["origin"] for r in policy["routes"]],"unresolvedOrigins":unresolved,
 "serviceId":observed["serviceId"],"sourceExecuted":False}))
'''


def remote_prepare(config, plan, code=REMOTE):
    service = (Path(__file__).resolve().parent / "managed_broker_service.py").read_bytes()
    payload = {**plan, "serviceBase64": base64.b64encode(service).decode(),
               "serviceSha256": hashlib.sha256(service).hexdigest()}
    transport = T.Transport(config)
    try:
        with transport.lease() as client:
            channel = client.get_transport().open_session(timeout=8)
            try:
                channel.settimeout(90)
                command = shlex.join(["/usr/bin/python3", "-I", "-c", code])
                password_name = config.ssh.get("sudoPasswordEnv")
                if password_name:
                    command = "sudo -k -S -p '' " + command
                channel.exec_command(command)
                prefix = (T.environment(password_name) + "\n").encode() if password_name else b""
                channel.sendall(prefix + json.dumps(payload, ensure_ascii=True).encode())
                channel.shutdown_write()
                body = channel.makefile("rb").read(65537)
                require(len(body) <= 65536 and channel.recv_exit_status() == 0, "SOURCE_SETUP_REMOTE_FAILED")
                result = T.decode(body)
                require(result["bundleSha256"] == plan["bundleSha256"]
                        and result["instanceId"] == plan["instanceId"]
                        and result["sourceExecuted"] is False, "SOURCE_SETUP_REMOTE_IDENTITY_CHANGED")
                if result.get("ok") is not True:
                    code = result.get("code")
                    require(False, code if code in {"SOURCE_SETUP_DNS_NO_ADDRESS",
                            "SOURCE_SETUP_DNS_NONPUBLIC", "SOURCE_SETUP_DNS_UNAVAILABLE",
                            "SOURCE_SETUP_CHANNEL_BUSY"}
                            else "SOURCE_SETUP_REMOTE_FAILED")
                return result
            finally:
                channel.close()
    finally:
        transport.close()


def append_runner(path, value, runner, *, source_id=None):
    previous = path.read_bytes()
    current = T.decode(previous)
    require(current == value, "SOURCE_SETUP_CONFIGURATION_CHANGED")
    matching = [row for row in current["runners"] if row["id"] == runner["id"]]
    if matching:
        require(matching == [runner], "SOURCE_SETUP_CONFIGURATION_CHANGED")
        return
    if source_id is not None:
        require(all(source_id not in row["sourceIds"] for row in current["runners"]),
                "SOURCE_SETUP_SOURCE_ALREADY_ROUTED")
    current["runners"].append(runner)
    atomic_json(path, current, previous)


def wait_tunnel(route, timeout=25):
    deadline = time.monotonic() + timeout
    while True:
        try:
            with socket.create_connection(("127.0.0.1", route["localPort"]), timeout=2) as peer:
                peer.settimeout(8)
                peer.sendall(T.frame(b'{"schemaVersion":1,"operation":"identity"}'))
                with peer.makefile("rb") as stream:
                    observed = T.identity_reply(T.read_frame(stream, T.MAX_REPLY), route)
            require(observed["ready"], "SOURCE_SETUP_CHANNEL_NOT_READY")
            return
        except (OSError, T.Failure):
            require(time.monotonic() < deadline, "SOURCE_SETUP_TUNNEL_PENDING")
            time.sleep(.25)


def prepare(value, routes_path, tunnels_path, *, transport=None):
    required = {"sourceId", "sourceSha256", "bundleSha256", "content"}
    require(isinstance(value, dict) and required <= set(value) <= required | {
        "sourceConfig", "sourceConfigSha256", "verifiedOriginRoute"}, "SOURCE_SETUP_REQUEST_INVALID")
    require(isinstance(value["sourceId"], str) and re.fullmatch(UUID, value["sourceId"])
            and all(isinstance(value[key], str) and re.fullmatch(SHA, value[key])
                    for key in ("sourceSha256", "bundleSha256"))
            and isinstance(value["content"], str)
            and len(value["content"].encode()) <= 1048576
            and hashlib.sha256(value["content"].encode()).hexdigest() == value["sourceSha256"],
            "SOURCE_SETUP_SOURCE_CHANGED")
    configuration_origins = configured_origins(value)
    if "verifiedOriginRoute" in value:
        from managed_origin_extension import prepare_extension
        return prepare_extension(value, routes_path, tunnels_path, transport=transport)
    try:
        origins = source_origins(value["content"])
    except Failure as error:
        if str(error) != "SOURCE_SETUP_ORIGINS_REQUIRED":
            raise
        origins = []
    origins.extend(origin for origin in configuration_origins if origin not in origins)
    token_groups = source_session_token_groups(value["content"])
    routes_path, tunnels_path = Path(routes_path).resolve(), Path(tunnels_path).resolve()
    require(routes_path.parent == tunnels_path.parent, "SOURCE_SETUP_CONFIGURATION_INVALID")
    with setup_lock(routes_path.parent):
        config = transport.config(tunnels_path) if transport else T.Config(tunnels_path)
        routes = T.decode(routes_path.read_bytes())
        require(routes.get("schemaVersion") == 1 and isinstance(routes.get("runners"), list),
                "SOURCE_SETUP_CONFIGURATION_INVALID")
        templates = {}
        for route in config.routes:
            if route["bundleSha256"] == value["bundleSha256"]:
                templates.setdefault(route["imageId"], route)
        require(templates, "SOURCE_SETUP_RUNTIME_UNAVAILABLE")
        capabilities = image_capabilities(routes_path.parent / "source-image-imports.json")
        known_modules = set().union(*capabilities.values()) if capabilities else set()
        selection = None
        failure = "SOURCE_SETUP_COMPANION_IMAGE_MISMATCH"
        for template in templates.values():
            try:
                imports = source_imports(value["content"])
                extra_origins, companions = companion_origins(
                    value["content"], routes_path.parent / "source-companions.json", template["imageId"],
                    imported_modules=imports)
                if capabilities is not None:
                    required = imports & known_modules
                    if not required <= capabilities.get(template["imageId"], set()):
                        failure = "SOURCE_SETUP_DEPENDENCY_IMAGE_MISMATCH"
                        continue
                    selection = {"imageId": template["imageId"], "requiredModules": sorted(required)}
                break
            except Failure as error:
                if str(error) != "SOURCE_SETUP_COMPANION_IMAGE_MISMATCH":
                    raise
        else:
            raise Failure(failure)
        origins.extend(origin for origin in extra_origins if origin not in origins)
        require(origins, "SOURCE_SETUP_ORIGINS_REQUIRED")
        require(len(origins) <= MAX_ROUTES, "SOURCE_SETUP_ORIGIN_LIMIT")
        directory = routes_path.parent / "source-channels"
        directory.mkdir(exist_ok=True)
        identity = {key: value[key] for key in ("sourceId", "sourceSha256", "bundleSha256")}
        identity["origins"] = origins
        if "sourceConfigSha256" in value:
            identity["sourceConfigSha256"] = value["sourceConfigSha256"]
        if companions:
            identity["companionModules"] = companions
        if selection:
            identity["imageImports"] = selection
        generation = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        # Failed plans belong to their frozen source/runtime; a new version gets a new owner.
        intent_path = directory / (value["sourceId"] + "-" + generation + ".json")
        if intent_path.exists():
            plan = T.decode(intent_path.read_bytes())
            require(all(plan[key] == value[key] for key in ("sourceId", "sourceSha256", "bundleSha256"))
                    and plan["origins"] == origins and plan.get("companionModules", []) == companions,
                    "SOURCE_SETUP_INTENT_CHANGED")
            require(plan.get("imageImports") == selection, "SOURCE_SETUP_INTENT_CHANGED")
            require(plan.get("sourceConfigSha256") == value.get("sourceConfigSha256"),
                    "SOURCE_SETUP_INTENT_CHANGED")
        else:
            require(not any(value["sourceId"] in row["sourceIds"] for row in routes["runners"]),
                    "SOURCE_SETUP_SOURCE_ALREADY_ROUTED")
            used = {r["localPort"] for r in config.routes}
            used.update(T.decode(path.read_bytes())["localPort"] for path in directory.glob("*.json"))
            port = next((p for p in range(19004, 19104) if p not in used), None)
            require(port is not None, "SOURCE_SETUP_CHANNEL_LIMIT")
            plan = {key: value[key] for key in ("sourceId", "sourceSha256", "bundleSha256")}
            if "sourceConfigSha256" in value:
                plan["sourceConfigSha256"] = value["sourceConfigSha256"]
            plan.update(origins=origins, sessionTokenGroups=token_groups, instanceId=str(uuid.uuid4()), localPort=port,
                        templateSocket=template["remoteSocket"])
            if companions:
                plan["companionModules"] = companions
            if selection:
                plan["imageImports"] = selection
            atomic_json(intent_path, plan)
        installed = transport.install(config, plan) if transport else remote_prepare(config, plan)
        require(not selection or installed["imageId"] == selection["imageId"],
                "SOURCE_SETUP_REMOTE_IDENTITY_CHANGED")
        route_id = "source-" + value["sourceId"]
        tunnel = {"id": route_id, "localPort": plan["localPort"], **{
            key: installed[key] for key in ("remoteSocket", "instanceId", "imageId",
                                            "bundleSha256", "egressPolicySha256", "httpImageId")}}
        append_runner(tunnels_path, config.document, tunnel)
        transport.wait(tunnel) if transport else wait_tunnel(tunnel)
        route = {"id": route_id, "label": "来源独立通道", "sourceIds": [value["sourceId"]],
                 "socket": transport.endpoint(tunnel) if transport else "tcp://127.0.0.1:" + str(plan["localPort"]), **{
            key: installed[key] for key in ("instanceId", "imageId", "egressPolicySha256", "httpImageId")}}
        append_runner(routes_path, routes, route, source_id=value["sourceId"])
        result = {"ok": True, "sourceId": value["sourceId"], "sourceSha256": value["sourceSha256"],
                "bundleSha256": value["bundleSha256"], "egressPolicySha256": installed["egressPolicySha256"],
                "approvedOrigins": installed["approvedOrigins"], "unresolvedOrigins": installed["unresolvedOrigins"],
                "companionModules": companions, "imageImports": selection or {},
                "sourceExecuted": False, "ready": True}
        if "sourceConfigSha256" in value:
            result["sourceConfigSha256"] = value["sourceConfigSha256"]
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--routes-file", type=Path, required=True)
    parser.add_argument("--tunnels-file", type=Path, required=True)
    args = parser.parse_args()
    body = sys.stdin.buffer.read(1500001)
    require(len(body) <= 1500000, "SOURCE_SETUP_REQUEST_INVALID")
    return prepare(T.decode(body), args.routes_file, args.tunnels_file)


if __name__ == "__main__":
    try:
        result = main()
    except Failure as error:
        result = {"ok": False, "code": str(error), "sourceExecuted": False}
    except BaseException:
        result = {"ok": False, "code": "SOURCE_SETUP_UNAVAILABLE", "sourceExecuted": False}
    print(json.dumps(result, ensure_ascii=True))
