"""Evidence-bound channel generations; old policies and journals remain immutable."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import uuid

import managed_runner_tunnel as T
import managed_source_setup as S
from source_origin_discovery import http_origin


REMOTE = r'''
import base64,hashlib,json,os,pathlib,sys
v=json.loads(sys.stdin.buffer.read(262145))
def check(ok):
 if not ok:raise RuntimeError("SOURCE_SETUP_REMOTE_FAILED")
def sha(body):return hashlib.sha256(body).hexdigest()
def put(path,body):
 if path.exists():check(path.is_file() and not path.is_symlink() and path.read_bytes()==body)
 else:
  with path.open("xb") as f:f.write(body);f.flush();os.fsync(f.fileno())
  path.chmod(0o600)
old=v["previousTunnel"]
oldroot=pathlib.Path(old["remoteSocket"]).parent
check(oldroot.resolve()==oldroot and oldroot.is_dir() and oldroot.stat().st_uid==0)
sys.path.insert(0,str(oldroot))
import managed_broker_service as L
oldpath,oldconfig,oldsha=L.configuration(oldroot/"broker-service.json")
check(all(oldconfig[k]==old[k] for k in ("instanceId","imageId","bundleSha256","egressPolicySha256")))
check((oldconfig["httpImageId"] or "")==old["httpImageId"])
if v.get("retire"):
 if (oldroot/"operator.sock").exists():
  observed=L.request(oldconfig,oldsha)
  L.request(oldconfig,oldsha,"stop",observed["serviceId"])
 L.wait_stopped(oldconfig)
 print(json.dumps({"ok":True,"bundleSha256":v["bundleSha256"],"instanceId":v["instanceId"],
  "sourceExecuted":False,"retired":True,"journalModified":False}))
 raise SystemExit(0)
observed=L.request(oldconfig,oldsha)
if observed["active"] or observed["pendingResult"] or observed["identity"]["recoveryRequired"]:
 print(json.dumps({"ok":False,"code":"SOURCE_SETUP_CHANNEL_BUSY","bundleSha256":v["bundleSha256"],
  "instanceId":v["instanceId"],"sourceExecuted":False}))
 raise SystemExit(0)
runtime=pathlib.Path(oldconfig["runtimeRoot"])
sys.path[:0]=[str(runtime/"scripts"),str(runtime/"vendor")]
import source_egress as E
base_policy=E.policy_from(json.loads(pathlib.Path(oldconfig["egressPolicyFile"]).read_bytes()))
origin=v["verifiedOriginRoute"]["candidateOrigin"]
if any(r["origin"]==origin and r.get("requestScope")=="origin" for r in base_policy["routes"]):
 print(json.dumps({"ok":True,"unchanged":True,"instanceId":v["instanceId"],
  "imageId":oldconfig["imageId"],"bundleSha256":oldconfig["bundleSha256"],
  "egressPolicySha256":oldconfig["egressPolicySha256"],"httpImageId":oldconfig["httpImageId"] or "",
  "approvedOrigins":[r["origin"] for r in base_policy["routes"]],
  "unresolvedOrigins":base_policy.get("pendingOrigins",[]),"sourceExecuted":False}))
 raise SystemExit(0)
root=oldroot.parent/("w-auto-"+v["instanceId"].replace("-","")[:12])
check(root.resolve()==root and root.parent==oldroot.parent and root!=oldroot)
if not root.exists():root.mkdir(mode=0o711)
check(root.is_dir() and not root.is_symlink() and root.stat().st_uid==0)
root.chmod(0o711)
identity={k:v[k] for k in ("sourceId","sourceSha256","bundleSha256","instanceId","verifiedOriginRoute","previousTunnel")}
put(root/"source-setup.json",json.dumps(identity,sort_keys=True,separators=(",",":")).encode())
policy_path=root/"egress-policy.json"
origin=v["verifiedOriginRoute"]["candidateOrigin"]
if not policy_path.exists():
 policy=E.policy_from(json.loads(pathlib.Path(oldconfig["egressPolicyFile"]).read_bytes()))
 check(policy["schemaVersion"]==3 and origin not in {r["origin"] for r in policy["routes"]})
 parsed,host,port=E.http_authority(origin)
 check(parsed.scheme=="https" and not parsed.path and not parsed.query and not parsed.fragment)
 try:addresses=E.public_dns_addresses(host,port=port)
 except E.Denied:
  print(json.dumps({"ok":False,"code":"SOURCE_SETUP_DNS_UNAVAILABLE","bundleSha256":v["bundleSha256"],
   "instanceId":v["instanceId"],"sourceExecuted":False}))
  raise SystemExit(0)
 route={"origin":origin,"exactUrls":[],"requestScope":"origin",**E.address_pins(addresses),
  "sessionToken":{"responseScope":"origin"}}
 headers=[r["origin"] for r in policy["routes"][:4]]
 if headers:route["headerOrigins"]=headers
 policy["routes"].append(route)
 if origin in policy.get("pendingOrigins",[]):policy["pendingOrigins"].remove(origin)
 E.policy_from(policy)
 put(policy_path,json.dumps(policy,ensure_ascii=True,separators=(",",":")).encode())
policy_body=policy_path.read_bytes()
policy=E.policy_from(json.loads(policy_body))
before=E.policy_from(json.loads(pathlib.Path(oldconfig["egressPolicyFile"]).read_bytes()))
added=[r for r in policy["routes"] if r["origin"]==origin]
check(len(added)==1 and added[0].get("requestScope")=="origin")
rest=json.loads(policy_body);rest["routes"].remove(added[0])
if origin in before.get("pendingOrigins",[]):rest["pendingOrigins"].append(origin)
check(rest==before)
journal=root/"journal"
if not journal.exists():journal.mkdir(mode=0o700)
check(journal.is_dir() and not journal.is_symlink() and journal.stat().st_uid==0)
journal.chmod(0o700)
service=base64.b64decode(v["serviceBase64"],validate=True)
check(sha(service)==v["serviceSha256"])
put(root/"managed_broker_service.py",service)
installed={**oldconfig,"root":str(root),"instanceId":v["instanceId"],
 "egressPolicyFile":str(policy_path),"egressPolicySha256":sha(policy_body)}
put(root/"broker-service.json",json.dumps(installed,sort_keys=True,separators=(",",":")).encode())
path,installed,config_sha=L.configuration(root/"broker-service.json")
if (root/"operator.sock").exists():observed=L.request(installed,config_sha)
else:observed=L.start(path,installed,config_sha)
check(observed["state"]=="RUNNING" and not observed["identity"]["recoveryRequired"])
print(json.dumps({"ok":True,"remoteSocket":str(root/"executor.sock"),"instanceId":installed["instanceId"],
 "imageId":installed["imageId"],"bundleSha256":installed["bundleSha256"],
 "egressPolicySha256":installed["egressPolicySha256"],"httpImageId":installed["httpImageId"] or "",
 "approvedOrigins":[r["origin"] for r in policy["routes"]],"unresolvedOrigins":policy.get("pendingOrigins",[]),
 "sourceExecuted":False}))
'''


def validated_grant(value):
    grant = value.get("verifiedOriginRoute")
    S.require(isinstance(grant, dict) and set(grant) == {
        "candidateOrigin", "requestedPageUrlSha256", "candidatePageUrlSha256",
        "businessChainVerified", "failureCauseEstablished"}
        and grant["businessChainVerified"] is False and grant["failureCauseEstablished"] is False
        and all(isinstance(grant[k], str) and re.fullmatch(S.SHA, grant[k])
                for k in ("requestedPageUrlSha256", "candidatePageUrlSha256")), "SOURCE_SETUP_EVIDENCE_INVALID")
    origin = grant["candidateOrigin"]
    S.require(isinstance(origin, str) and origin.startswith("https://") and http_origin(origin) == origin,
              "SOURCE_SETUP_EVIDENCE_INVALID")
    return grant


def prepare_extension(value, routes_path, tunnels_path, *, transport=None):
    grant = validated_grant(value)
    routes_path, tunnels_path = Path(routes_path).resolve(), Path(tunnels_path).resolve()
    S.require(routes_path.parent == tunnels_path.parent, "SOURCE_SETUP_CONFIGURATION_INVALID")
    with S.setup_lock(routes_path.parent):
        config = transport.config(tunnels_path) if transport else T.Config(tunnels_path)
        routes_raw = routes_path.read_bytes()
        routes = T.decode(routes_raw)
        matching = [r for r in routes["runners"] if value["sourceId"] in r["sourceIds"]]
        S.require(len(matching) == 1, "SOURCE_SETUP_SOURCE_ROUTE_REQUIRED")
        current = matching[0]
        identity = {k: value[k] for k in ("sourceId", "sourceSha256", "bundleSha256", "verifiedOriginRoute")}
        if "sourceConfigSha256" in value:
            identity["sourceConfigSha256"] = value["sourceConfigSha256"]
        generation = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        directory = routes_path.parent / "source-channels"
        directory.mkdir(exist_ok=True)
        intent = directory / (value["sourceId"] + "-origin-" + generation + ".json")
        route_id = "origin-" + value["sourceId"].replace("-", "") + "-" + generation[:12]
        if intent.exists():
            plan = T.decode(intent.read_bytes())
            S.require(all(plan[k] == item for k, item in identity.items()), "SOURCE_SETUP_INTENT_CHANGED")
        else:
            old = next((r for r in config.routes if r["id"] == current["id"]), None)
            S.require(old is not None and old["bundleSha256"] == value["bundleSha256"]
                      and all(current[k] == old[k] for k in ("instanceId", "imageId", "egressPolicySha256", "httpImageId")),
                      "SOURCE_SETUP_CONFIGURATION_CHANGED")
            used = {r["localPort"] for r in config.routes}
            used.update(T.decode(p.read_bytes())["localPort"] for p in directory.glob("*.json"))
            port = next((p for p in range(19004, 19104) if p not in used), None)
            S.require(port is not None, "SOURCE_SETUP_CHANNEL_LIMIT")
            plan = {**identity, "instanceId": str(uuid.uuid4()), "localPort": port,
                    "previousRoute": current, "previousTunnel": old}
            S.atomic_json(intent, plan)
        previous = plan["previousRoute"]
        S.require(current == previous or current["id"] == route_id, "SOURCE_SETUP_CONFIGURATION_CHANGED")
        completed = intent.with_suffix(".completed")
        if completed.exists():
            result = T.decode(completed.read_bytes())
            S.require(current["id"] == result["routeId"] and current["egressPolicySha256"] == result["egressPolicySha256"],
                      "SOURCE_SETUP_CONFIGURATION_CHANGED")
            ready = next(r for r in config.routes if r["id"] == result["routeId"])
            transport.wait(ready) if transport else S.wait_tunnel(ready)
            return result
        installed_path = intent.with_suffix(".installed")
        if installed_path.exists():
            installed = T.decode(installed_path.read_bytes())
        else:
            installed = transport.install(config, plan, REMOTE) if transport else S.remote_prepare(config, plan, REMOTE)
            S.require(all(installed[k] == plan["previousTunnel"][k]
                          for k in ("imageId", "bundleSha256", "httpImageId")), "SOURCE_SETUP_REMOTE_IDENTITY_CHANGED")
            S.atomic_json(installed_path, installed)
        if installed.get("unchanged"):
            S.require(current == previous, "SOURCE_SETUP_CONFIGURATION_CHANGED")
            transport.wait(plan["previousTunnel"]) if transport else S.wait_tunnel(plan["previousTunnel"])
            result = {"ok": True, **{k: value[k] for k in ("sourceId", "sourceSha256", "bundleSha256")},
                      "verifiedOriginRoute": grant, "egressPolicySha256": installed["egressPolicySha256"],
                      "approvedOrigins": installed["approvedOrigins"], "sourceExecuted": False,
                      "ready": True, "routeId": previous["id"], "previousChannelRetired": False}
            if "sourceConfigSha256" in value:
                result["sourceConfigSha256"] = value["sourceConfigSha256"]
            S.atomic_json(completed, result)
            return result
        tunnel = {"id": route_id, "localPort": plan["localPort"], **{k: installed[k] for k in (
            "remoteSocket", "instanceId", "imageId", "bundleSha256", "egressPolicySha256", "httpImageId")}}
        S.append_runner(tunnels_path, config.document, tunnel)
        transport.wait(tunnel) if transport else S.wait_tunnel(tunnel)
        if current == previous:
            replacement = {**previous, "id": route_id, "sourceIds": [value["sourceId"]],
                           "socket": transport.endpoint(tunnel) if transport else "tcp://127.0.0.1:" + str(plan["localPort"]),
                           "instanceId": installed["instanceId"], "egressPolicySha256": installed["egressPolicySha256"]}
            updated = json.loads(json.dumps(routes))
            original = next(r for r in updated["runners"] if r["id"] == previous["id"])
            original["sourceIds"].remove(value["sourceId"])
            if not original["sourceIds"]:
                updated["runners"].remove(original)
            updated["runners"].append(replacement)
            S.atomic_json(routes_path, updated, routes_raw)
            routes = updated
        retired = not any(r["id"] == previous["id"] for r in routes["runners"])
        if retired:
            result = (transport.install(config, {**plan, "retire": True}, REMOTE) if transport
                      else S.remote_prepare(config, {**plan, "retire": True}, REMOTE))
            S.require(result.get("retired") is True and result.get("journalModified") is False,
                      "SOURCE_SETUP_RETIREMENT_UNCONFIRMED")
            raw = tunnels_path.read_bytes()
            updated = T.decode(raw)
            updated["runners"] = [r for r in updated["runners"] if r != plan["previousTunnel"]]
            S.atomic_json(tunnels_path, updated, raw)
        result = {"ok": True, **{k: value[k] for k in ("sourceId", "sourceSha256", "bundleSha256")},
                  "verifiedOriginRoute": grant, "egressPolicySha256": installed["egressPolicySha256"],
                  "approvedOrigins": installed["approvedOrigins"], "unresolvedOrigins": installed["unresolvedOrigins"],
                  "routeId": route_id, "previousChannelRetired": retired, "sourceExecuted": False, "ready": True}
        if "sourceConfigSha256" in value:
            result["sourceConfigSha256"] = value["sourceConfigSha256"]
        S.atomic_json(completed, result)
        return result
