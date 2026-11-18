"""Fixed, network-none AT HTTP driver and current-child evidence validation."""
from __future__ import annotations

import copy
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time

import managed_source_protocol as M

S = M.S
MAIN = "cn.har01d.maintenance.lab.ManagedT4HttpAcceptance"
CLASSPATH = "/opt/at-maintenance/http/classes:/opt/at-maintenance/http/lib/*"
POLICY = {"timeoutSeconds": 600, "memoryBytes": 805306368, "pidsLimit": 128,
          "network": "none", "user": "65532:65532", "maxOutputBytes": 65536}
CALL_FIELDS = {"ordinal", "shape", "playType", "method", "httpStatus", "t4CallSha256",
               "brokerJobId", "resultSha256", "httpResponseSha256", "httpResponseBytes",
               "workerReturnedAfterCleanup"}
DRIVER_FAILURE_CODES = {
    "HTTP_DRIVER_FAILED", "HTTP_CLEANUP_UNCONFIRMED", "HTTP_HOME_EMPTY", "HTTP_CARDS_EMPTY",
    "HTTP_CARD_CONTRACT_FAILED", "HTTP_PAGINATION_FAILED", "HTTP_DETAIL_EPISODE_MISSING",
    "HTTP_DETAIL_SAMPLE_MISMATCH", "HTTP_PLAYER_INVALID", "HTTP_SEARCH_SHAPE_INVALID",
    "HTTP_PROXY_BODY_LIMIT", "HTTP_MANIFEST_URI_INVALID", "HTTP_MANIFEST_EMPTY",
    "T4_BROKER_TIMEOUT", "T4_BROKER_REJECTED", "T4_BROKER_RECEIPT_MISMATCH",
    "T4_BROKER_DELIVERY_MISMATCH", "T4_BROKER_SESSION_CONTEXT_CHANGED",
    "T4_BROKER_UNIX_SOCKET_UNAVAILABLE", "T4_BROKER_CLEANUP_UNCONFIRMED",
    "T4_HTTP_OBSERVATION_PLAN_MISMATCH", "T4_HTTP_OBSERVATION_DISPATCH_MISMATCH",
    "T4_HTTP_OBSERVATION_DELIVERY_MISMATCH", "T4_HTTP_OBSERVATION_RESPONSE_MISMATCH",
    "T4_HTTP_OBSERVATION_SESSION_MISMATCH", "T4_HTTP_OBSERVATION_PROXY_MISMATCH",
}


class DriverFailure(S.Failure):
    def __init__(self, report):
        super().__init__("T4_HTTP_DRIVER_FAILED")
        self.diagnostic = {
            "schemaVersion": 1, "authority": "at_t4_http_driver_observed",
            "code": report["code"] if report.get("code") in DRIVER_FAILURE_CODES else "HTTP_DRIVER_FAILED",
            "completedActions": report["actions"], "cleanupConfirmed": report["cleanupConfirmed"],
        }


def failed_driver_report(value):
    return (matches(value, {"completed": False, "httpOwners": "real_AT_lab_owners",
                            "pythonBroker": True, "fullAtApplication": False})
            and type(value.get("actions")) is int and 0 <= value["actions"] <= 36
            and type(value.get("cleanupConfirmed")) is bool and isinstance(value.get("code"), str))


def sha(value):
    return isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value) is not None


def matches(value, expected):
    return isinstance(value, dict) and all(type(value.get(key)) is type(item) and value[key] == item
                                          for key, item in expected.items())


def verify_observation(value, binding, children):
    """Only current broker rows can corroborate a driver receipt; never import history."""
    expected = {"schemaVersion": 1, "authority": "at_t4_http_owner_observed",
                "scope": "t4-http-parameters-v1",
                **{key: binding[key] for key in (
                    "jobId", "instanceId", "imageId", "bundleSha256", "runtimeSha256",
                    "executionPolicySha256", "egressPolicySha256", "sourceSha256", "inputProfileSha256")},
                "allPlannedCallsObserved": True, "rawOutputStored": False,
                "formalAcceptanceIntegrated": False, "productNodePassesAdded": 0, "publishEligible": False}
    if isinstance(value, dict) and "sessionMode" in value:
        return verify_session_observation(value, expected, children, binding)
    S.require(matches(value, expected) and set(value) == set(expected) | {"calls"}
              and isinstance(value["calls"], list) and len(value["calls"]) == 36
              and isinstance(children, dict) and len(children) == 36, "T4_HTTP_RECEIPT_MISMATCH")
    jobs = set()
    for index, call in enumerate(value["calls"]):
        mode = ("t4", "t4-direct")[index // 18]
        method = ("home", "category", "category", "detail", "search", "play")[index % 6]
        S.require(matches(call, {"ordinal": index + 1, "shape": ("get", "form", "json")[(index // 6) % 3],
                                "playType": mode, "method": method, "httpStatus": 200,
                                "workerReturnedAfterCleanup": True})
                  and set(call) == CALL_FIELDS and type(call["httpResponseBytes"]) is int
                  and 0 < call["httpResponseBytes"] <= 65536
                  and all(sha(call[key]) for key in (
                      "t4CallSha256", "resultSha256", "httpResponseSha256")),
                  "T4_HTTP_RECEIPT_MISMATCH")
        job = call["brokerJobId"]
        S.require(isinstance(job, str) and re.fullmatch(
            r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", job)
                  and job != binding["jobId"] and job not in jobs, "T4_HTTP_RECEIPT_MISMATCH")
        jobs.add(job)
        row = children.get(job, {})
        S.require(matches(row, {"schemaVersion": 1, "authority": "managed_source_broker_observed",
                               "jobId": job, "parentJobId": binding["jobId"],
                               "parentProfileSha256": binding["inputProfileSha256"],
                               "t4CallSha256": call["t4CallSha256"], "status": "COMPLETED",
                               "businessChainVerified": False, "publishEligible": False,
                               **{key: binding[key] for key in (
                                   "instanceId", "imageId", "bundleSha256", "executionPolicySha256",
                                   "egressPolicySha256", "sourceSha256")}})
                  and row.get("cleanup") == {"confirmed": True, "containerAbsent": True,
                                            "basis": "create_ack_exact_id_absent_and_worker_exited"},
                  "T4_HTTP_RECEIPT_MISMATCH")
        result = row.get("result", {})
        rpc = result.get("t4RpcValidation", {})
        S.require(matches(result, {"schemaVersion": 1, "authority": "managed_source_host_observed",
                                  "executionKind": "t4_single_action_rpc", "rawOutputStored": False,
                                  "businessChainVerified": False, "publishEligible": False})
                  and matches(rpc, {
                      "schemaVersion": 1, "authority": "host_t4_rpc_transport_observed",
                      "inputSourceSha256": binding["sourceSha256"], "runtimeSha256": binding["runtimeSha256"],
                      "method": method, "playType": mode, "resultSha256": call["resultSha256"],
                      "actionReturned": True, "destroyReturned": True,
                      "initializationKind": "credential_free_local_source_projection",
                      "fullGeneratedExtForwarded": False, "businessChainVerified": False,
                      "productNodePassesAdded": 0, "publishEligible": False, "rawOutputStored": False,
                      "cleanup": {"confirmed": True, "containerAbsent": True}})
                  and sha(rpc.get("loadedTextSha256")) and sha(rpc.get("paramsSha256")),
                  "T4_HTTP_RECEIPT_MISMATCH")
    S.require(jobs == set(children), "T4_HTTP_RECEIPT_MISMATCH")
    return copy.deepcopy(value)


SESSION_FIELDS = {"brokerJobId", "runtimeSha256", "loadedTextSha256", "playType",
                  "sourceDataPresent", "sourceDataSha256", "initCount", "destroyReturned",
                  "actions", "cleanup", "initializationKind", "fullGeneratedExtForwarded"}
IDENTITY_FIELDS = {"sourceRuleSha256", "dependencySha256"}
ACTION_FIELDS = {"ordinal", "method", "t4CallSha256", "paramsSha256", "resultSha256", "workerCallId"}
PROXY_FIELDS = {"ordinal", "playerOrdinal", "brokerJobId", "sessionOrdinal", "httpMethod",
                "httpStatus", "t4CallSha256", "resultSha256", "targetSha256", "responseSha256",
                "responseBytes", "contentLength", "mime", "contentRange"}
MANIFEST_FIELDS = {"sourceBodySha256", "sourceBytes", "segmentTargetSha256"}


def manifest_mime(mime):
    return isinstance(mime, str) and re.fullmatch(
        r"(?:application/vnd\.apple\.mpegurl|application/x-mpegurl)(?:; *charset=UTF-8)?",
        mime, re.IGNORECASE) is not None


def verify_session_observation(value, expected, children, binding):
    """Corroborate every delivered HTTP/proxy action against this parent's current session rows."""
    failure = "T4_HTTP_RECEIPT_MISMATCH"
    S.require(type(binding.get("sourceDataPresent")) is bool and sha(binding.get("sourceDataSha256")), failure)
    data_identity = {key: binding[key] for key in ("sourceDataPresent", "sourceDataSha256")}
    S.require(matches(value, {**expected, "sessionMode": True})
              and set(value) == set(expected) | {"calls", "sessionMode", "sessionReceipts", "proxyCalls"}
              and isinstance(value["calls"], list) and len(value["calls"]) == 36
              and isinstance(value["sessionReceipts"], list) and len(value["sessionReceipts"]) == 2
              and isinstance(value["proxyCalls"], list) and len(value["proxyCalls"]) <= 12
              and len(value["proxyCalls"]) % 2 == 0 and isinstance(children, dict) and len(children) == 2, failure)
    identity_present = any(
        isinstance(session, dict) and any(key in session for key in IDENTITY_FIELDS)
        for session in value["sessionReceipts"])
    session_fields = SESSION_FIELDS | IDENTITY_FIELDS if identity_present else SESSION_FIELDS
    actions, jobs = {}, set()
    for index, session in enumerate(value["sessionReceipts"]):
        mode = ("t4", "t4-direct")[index]
        S.require(isinstance(session, dict) and set(session) == session_fields, failure)
        job = session["brokerJobId"]
        S.require(isinstance(job, str) and re.fullmatch(
            r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", job)
                  and job != expected["jobId"] and job not in jobs, failure)
        jobs.add(job)
        row = children.get(job, {})
        S.require(matches(row, {"schemaVersion": 1, "authority": "managed_source_broker_observed",
                               "jobId": job, "parentJobId": expected["jobId"],
                               "parentProfileSha256": expected["inputProfileSha256"],
                               "status": "COMPLETED", "executionKind": "t4_bounded_session",
                               "businessChainVerified": False, "publishEligible": False,
                               **{key: expected[key] for key in (
                                   "instanceId", "imageId", "bundleSha256", "executionPolicySha256",
                                   "egressPolicySha256", "sourceSha256")}})
                  and row.get("cleanup") == {"confirmed": True, "containerAbsent": True,
                                            "basis": "create_ack_exact_id_absent_and_worker_exited"}, failure)
        result = row.get("result", {})
        receipt = result.get("t4SessionValidation", {})
        S.require(matches(result, {"schemaVersion": 1, "authority": "managed_source_host_observed",
                                  "executionKind": "t4_bounded_session", "rawOutputStored": False,
                                  "businessChainVerified": False, "publishEligible": False})
                  and matches(receipt, {"schemaVersion": 1, "authority": "host_t4_session_observed",
                                        "inputSourceSha256": expected["sourceSha256"],
                                        "rawOutputStored": False, "businessChainVerified": False,
                                        "publishEligible": False})
                  and all(type(session[key]) is type(receipt.get(key)) and session[key] == receipt[key]
                          for key in SESSION_FIELDS - {"brokerJobId"})
                  and matches(session, {"playType": mode, "runtimeSha256": expected["runtimeSha256"],
                                        "initCount": 1, "destroyReturned": True,
                                        "initializationKind": "credential_free_local_source_projection",
                                        "fullGeneratedExtForwarded": False,
                                        "cleanup": {"confirmed": True, "containerAbsent": True}})
                  and type(session["sourceDataPresent"]) is bool and sha(session["sourceDataSha256"])
                  and matches(session, data_identity)
                  and sha(session["loadedTextSha256"]) and isinstance(session["actions"], list)
                  and 18 <= len(session["actions"]) <= 24
                  and row.get("sessionActions") == session["actions"], failure)
        if identity_present:
            S.require(all(sha(session.get(key)) for key in IDENTITY_FIELDS), failure)
            S.require(all(receipt.get(key) == session[key] for key in IDENTITY_FIELDS), failure)
            if index:
                first = value["sessionReceipts"][0]
                S.require(all(session[key] == first[key] for key in IDENTITY_FIELDS), failure)
        if index:
            first = value["sessionReceipts"][0]
            S.require(all(session[key] == first[key] for key in (
                "sourceDataPresent", "sourceDataSha256", "loadedTextSha256")), failure)
        for ordinal, action in enumerate(session["actions"], 1):
            S.require(matches(action, {"ordinal": ordinal, "workerCallId": ordinal + 1})
                      and set(action) == ACTION_FIELDS and action.get("method") in {
                          "home", "category", "detail", "search", "play", "proxy"}
                      and all(sha(action.get(key)) for key in (
                          "t4CallSha256", "paramsSha256", "resultSha256")), failure)
            actions[(job, ordinal)] = action
        S.require(row.get("t4CallSha256") == session["actions"][0]["t4CallSha256"], failure)
    S.require(jobs == set(children), failure)
    used = set()
    def action_for(call, method):
        key = (call.get("brokerJobId"), call.get("sessionOrdinal"))
        S.require(type(call.get("sessionOrdinal")) is int and key in actions and key not in used, failure)
        action = actions[key]
        S.require(action["method"] == method and all(action[name] == call[name] for name in (
            "t4CallSha256", "resultSha256")), failure)
        used.add(key)

    for index, call in enumerate(value["calls"]):
        method = ("home", "category", "category", "detail", "search", "play")[index % 6]
        session = value["sessionReceipts"][index // 18]
        S.require(matches(call, {"ordinal": index + 1, "shape": ("get", "form", "json")[(index // 6) % 3],
                                "playType": session["playType"], "method": method, "httpStatus": 200,
                                "brokerJobId": session["brokerJobId"],
                                "workerReturnedAfterCleanup": False, "cleanupPending": True})
                  and set(call) == CALL_FIELDS | {"sessionOrdinal", "cleanupPending"}
                  and type(call.get("httpResponseBytes")) is int and 0 < call["httpResponseBytes"] <= 65536
                  and sha(call.get("httpResponseSha256")), failure)
        action_for(call, method)
    prior = {}
    for index, proxy in enumerate(value["proxyCalls"]):
        first = index % 2 == 0
        S.require(isinstance(proxy, dict), failure)
        is_manifest = manifest_mime(proxy.get("mime"))
        hls_segment = not first and manifest_mime(prior.get("mime"))
        S.require(set(proxy) == PROXY_FIELDS | (MANIFEST_FIELDS if is_manifest else set())
                  and matches(proxy, {"ordinal": index + 1})
                  and type(proxy["playerOrdinal"]) is int and proxy["playerOrdinal"] in (6, 12, 18, 24, 30, 36)
                  and type(proxy["httpStatus"]) is int and proxy["httpStatus"] in (200, 206)
                  and type(proxy["responseBytes"]) is int
                  and 0 <= proxy["responseBytes"] <= (524288 if is_manifest else 32768)
                  and type(proxy["contentLength"]) is int
                  and 0 < proxy["contentLength"] <= (524288 if is_manifest else 32768)
                  and isinstance(proxy["mime"], str)
                  and sha(proxy["responseSha256"]) and sha(proxy["targetSha256"]), failure)
        player = value["calls"][proxy["playerOrdinal"] - 1]
        S.require(proxy["brokerJobId"] == player["brokerJobId"]
                  and proxy["sessionOrdinal"] == player["sessionOrdinal"] + index % 2 + 1, failure)
        if first:
            S.require(not prior or proxy["playerOrdinal"] > prior["playerOrdinal"], failure)
        else:
            S.require(all(proxy[key] == prior[key] for key in ("playerOrdinal", "brokerJobId")), failure)
        is_segment = re.fullmatch(
            r"video/mp2t(?:; *charset=UTF-8)?", proxy["mime"], re.IGNORECASE) is not None
        if is_manifest:
            S.require(first and proxy["httpMethod"] == "GET" and proxy["httpStatus"] == 200
                      and proxy["contentRange"] == "" and proxy["responseBytes"] == proxy["contentLength"]
                      and proxy["responseBytes"] > 0
                      and type(proxy["sourceBytes"]) is int and 0 < proxy["sourceBytes"] <= 524288
                      and sha(proxy["sourceBodySha256"]) and sha(proxy["segmentTargetSha256"]), failure)
        else:
            S.require(is_segment and proxy["httpMethod"] == ("GET" if first or hls_segment else "HEAD"), failure)
        if proxy["httpStatus"] == 206:
            match = re.fullmatch(r"bytes 0-([0-9]{1,5})/([0-9]{1,20})", proxy["contentRange"])
            S.require(match is not None and int(match[1]) + 1 == proxy["contentLength"]
                      and int(match[2]) > int(match[1]), failure)
        else:
            S.require(proxy["contentRange"] == "", failure)
        if hls_segment:
            S.require(proxy["httpMethod"] == "GET" and proxy["responseBytes"] == proxy["contentLength"]
                      and proxy["responseBytes"] >= 564
                      and proxy["targetSha256"] == prior["segmentTargetSha256"], failure)
        elif not is_manifest and not first:
            S.require(proxy["responseBytes"] == 0 and proxy["responseSha256"] == S.digest(b"")
                      and all(proxy[key] == prior[key] for key in (
                          "playerOrdinal", "brokerJobId", "targetSha256", "httpStatus", "mime",
                          "contentLength", "contentRange")), failure)
        elif is_manifest:
            S.require(proxy["responseBytes"] > 0, failure)
        else:
            S.require(proxy["responseBytes"] == proxy["contentLength"] and proxy["responseBytes"] >= 564
                      and (not prior or proxy["playerOrdinal"] > prior["playerOrdinal"]), failure)
        action_for(proxy, "proxy")
        prior = proxy
    S.require(used == set(actions), failure)
    # Each play has only its two bounded callbacks between business actions.
    for index, session in enumerate(value["sessionReceipts"]):
        ordered = []
        for call in value["calls"][index * 18:(index + 1) * 18]:
            ordered.append(call["sessionOrdinal"])
            ordered.extend(row["sessionOrdinal"] for row in value["proxyCalls"]
                           if row["playerOrdinal"] == call["ordinal"])
        S.require(ordered == list(range(1, len(session["actions"]) + 1)), failure)
    return copy.deepcopy(value)


def exchange(command, payload, cancelled):
    env = {key: value for key, value in os.environ.items()
           if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    with tempfile.TemporaryDirectory(prefix="at-http-docker-config-") as config:
        env["DOCKER_CONFIG"] = config
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, env=env)
        output, errors = bytearray(), bytearray()
        lock = threading.Lock()
        exceeded, write_failed = threading.Event(), threading.Event()

        def read(stream, target):
            with stream:
                while chunk := stream.read1(4096):
                    with lock:
                        available = max(0, POLICY["maxOutputBytes"] - len(output) - len(errors))
                        target.extend(chunk[:available])
                        if len(chunk) > available:
                            exceeded.set()
                    if exceeded.is_set():
                        process.kill()
                        return

        def write():
            try:
                process.stdin.write(payload)
                process.stdin.close()
            except (OSError, ValueError):
                write_failed.set()

        threads = [threading.Thread(target=read, args=(process.stdout, output), daemon=True),
                   threading.Thread(target=read, args=(process.stderr, errors), daemon=True),
                   threading.Thread(target=write, daemon=True)]
        try:
            for thread in threads:
                thread.start()
            deadline = time.monotonic() + POLICY["timeoutSeconds"]
            while process.poll() is None:
                M.check_cancelled(cancelled)
                S.require(time.monotonic() < deadline, "ENGINE_TIMEOUT")
                time.sleep(0.02)
            for thread in threads:
                thread.join(timeout=3)
            S.require(not any(thread.is_alive() for thread in threads), "ENGINE_STREAM_UNCLOSED")
            S.require(not exceeded.is_set(), "ENGINE_OUTPUT_LIMIT")
            S.require(not write_failed.is_set(), "T4_HTTP_DRIVER_FAILED")
            M.check_cancelled(cancelled)
            if process.returncode != 0:
                try:
                    report = S.decode(bytes(output))
                except S.Failure:
                    raise S.Failure("T4_HTTP_DRIVER_FAILED") from None
                if failed_driver_report(report):
                    raise DriverFailure(report)
                raise S.Failure("T4_HTTP_DRIVER_FAILED")
            return S.decode(bytes(output))
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=3)
            for thread in threads:
                if thread.ident is not None:
                    thread.join(timeout=3)
            if not process.stdin.closed:
                process.stdin.close()


class Driver:
    """Configuration is host-owned. Requests cannot supply images, paths or commands."""
    def __init__(self, engine, image_id, endpoint, job):
        self.engine, self.image_id, self.endpoint, self.job = engine, image_id, Path(endpoint), job
        self.name = "at-maintenance-http-" + job
        self.ident = None
        self.issued = False
        self.cleanup = {"confirmed": True, "containerAbsent": True}

    def lookup(self):
        raw = self.engine.call(["container", "ls", "--all", "--no-trunc", "--filter",
                                "name=^/" + self.name + "$", "--format", "{{.ID}}"]).decode("ascii").strip()
        S.require(not raw or sha(raw), "CONTAINER_LOOKUP_INVALID")
        return raw or None

    def owned(self, document):
        S.require(document.get("Id") == self.ident and document.get("Name") == "/" + self.name
                  and document.get("Image") == self.image_id
                  and matches(document.get("Config", {}).get("Labels"), {
                      S.MANAGED + ".kind": "at-http-acceptance-v1", S.MANAGED + ".job": self.job}),
                  "CONTAINER_OWNERSHIP_MISMATCH")

    def validate(self, document):
        self.owned(document)
        config, host = document.get("Config", {}), document.get("HostConfig", {})
        mounts = document.get("Mounts", [])
        S.require(config.get("User") == "65532:65532" and config.get("Entrypoint") == ["java"]
                  and config.get("Cmd") == ["-cp", CLASSPATH, MAIN]
                  and host.get("NetworkMode") == "none" and host.get("ReadonlyRootfs") is True
                  and host.get("Privileged") is False and host.get("CapDrop") == ["ALL"]
                  and host.get("SecurityOpt") == ["no-new-privileges:true"]
                  and host.get("Memory") == host.get("MemorySwap") == POLICY["memoryBytes"]
                  and host.get("NanoCpus") == 1_000_000_000 and host.get("PidsLimit") == 128
                  and host.get("RestartPolicy", {}).get("Name") == "no"
                  and host.get("LogConfig", {}).get("Type") == "none"
                  and not any(host.get(key) for key in (
                      "PortBindings", "Devices", "DeviceRequests", "VolumesFrom"))
                  and len(mounts) == 1 and mounts[0].get("Type") == "bind"
                  and mounts[0].get("Source") == str(self.endpoint)
                  and mounts[0].get("Destination") == "/run/at-maintenance/executor.sock"
                  and mounts[0].get("RW") is False
                  and host.get("Tmpfs") == {"/tmp": "rw,noexec,nosuid,nodev,size=67108864,mode=1777"},
                  "CONTAINER_CONFIG_MISMATCH")
        S.require(all(not re.match(
            r"(FN_NAS_|MAINTENANCE_|SPRING_SECURITY_|JAVA_TOOL_OPTIONS=|JDK_JAVA_OPTIONS=|_JAVA_OPTIONS=)", item)
                      for item in config.get("Env", [])), "CONTAINER_ENV_MISMATCH")

    def remove(self):
        if not self.issued:
            return
        if self.ident is None:
            raise S.Failure("MANAGED_CLEANUP_UNCONFIRMED")
        rows = self.engine.document(["container", "inspect", self.ident])
        S.require(isinstance(rows, list) and len(rows) == 1, "CONTAINER_INSPECT_INVALID")
        self.owned(rows[0])
        try:
            self.engine.call(["container", "rm", "--force", self.ident])
        except S.Failure as error:
            S.require(error.code in {"ENGINE_TIMEOUT", "ENGINE_COMMAND_FAILED", "ENGINE_STREAM_UNCLOSED"},
                      "MANAGED_CLEANUP_UNCONFIRMED")
        self.confirm_absent(wait=True)

    def confirm_absent(self, *, wait=False):
        S.require(sha(self.ident), "MANAGED_CLEANUP_UNCONFIRMED")
        deadline = time.monotonic() + 10
        while True:
            by_id = self.engine.call(["container", "ls", "--all", "--no-trunc", "--filter",
                                      "id=" + self.ident, "--format", "{{.ID}}"]).decode("ascii").strip()
            by_name = self.lookup()
            S.require(by_id in {"", self.ident} and by_name in {None, self.ident},
                      "CONTAINER_OWNERSHIP_MISMATCH")
            if not by_id and by_name is None:
                self.cleanup = {"confirmed": True, "containerAbsent": True}
                return
            S.require(wait and time.monotonic() < deadline, "MANAGED_CLEANUP_UNCONFIRMED")
            time.sleep(0.1)

    def run(self, plan, cancelled):
        try:
            M.check_cancelled(cancelled)
            rows = self.engine.document(["image", "inspect", self.image_id])
            S.require(isinstance(rows, list) and len(rows) == 1 and rows[0].get("Id") == self.image_id
                      and rows[0].get("Os") == "linux" and not rows[0].get("Config", {}).get("Volumes"),
                      "LOCAL_IMAGE_MISMATCH")
            if self.lookup() is not None:
                self.cleanup = {"confirmed": False, "containerAbsent": False}
                raise S.Failure("JOB_ALREADY_EXISTS")
            M.check_cancelled(cancelled)
            self.issued = True
            self.cleanup = {"confirmed": False, "containerAbsent": False}
            raw = self.engine.call([
                "container", "create", "--name", self.name, "--pull", "never", "--interactive", "--read-only",
                "--user", "65532:65532", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                "--network", "none", "--memory", str(POLICY["memoryBytes"]), "--memory-swap",
                str(POLICY["memoryBytes"]), "--cpus", "1", "--pids-limit", "128", "--restart", "no",
                "--no-healthcheck", "--log-driver", "none",
                "--mount", "type=bind,src=" + str(self.endpoint) + ",dst=/run/at-maintenance/executor.sock,readonly",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=67108864,mode=1777",
                "--label", S.MANAGED + ".kind=at-http-acceptance-v1",
                "--label", S.MANAGED + ".job=" + self.job,
                "--entrypoint", "java", self.image_id, "-cp", CLASSPATH, MAIN], timeout=30)
            ident = raw.decode("ascii").strip()
            S.require(sha(ident), "CONTAINER_CREATE_INVALID")
            self.ident = ident
            rows = self.engine.document(["container", "inspect", ident])
            S.require(isinstance(rows, list) and len(rows) == 1, "CONTAINER_INSPECT_INVALID")
            self.validate(rows[0])
            M.check_cancelled(cancelled)
            result = exchange([*self.engine.command, "container", "start", "--attach", "--interactive", ident],
                              S.encode(plan), cancelled)
            rows = self.engine.document(["container", "inspect", ident])
            S.require(isinstance(rows, list) and len(rows) == 1, "CONTAINER_INSPECT_INVALID")
            self.validate(rows[0])
            S.require(matches(rows[0].get("State"), {"Status": "exited", "ExitCode": 0, "OOMKilled": False})
                      and matches(result, {"completed": True, "actions": 36, "cleanupConfirmed": True,
                                           "httpOwners": "real_AT_lab_owners", "pythonBroker": True,
                                           "sourceExecutedInContainer": True, "fullAtApplication": False,
                                           "configurationDataConnected": True, "playbackVerified": False}),
                      "T4_HTTP_DRIVER_FAILED")
            return result["httpObservation"]
        finally:
            self.remove()
