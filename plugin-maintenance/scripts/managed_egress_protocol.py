"""Bounded source-to-broker stdio exchange, retaining a network-none source container."""
from __future__ import annotations

from collections import deque
from http.client import HTTPException
import os
import copy
import re
import subprocess
import tempfile
import threading
import time

import managed_source_protocol as M
from managed_loader import Loader, T4Call, T4Session, Invalid
from source_egress import Denied, MAX_REQUESTS

S = M.S
KIND = "managed-source-broker-v1"
POLICY = {**M.POLICY, "mode": KIND, "runtimeTimeoutSeconds": 60, "acceptanceTimeoutSeconds": 360}
ENTRY = "/opt/at-maintenance/managed/egress_entry.py"
MAX_LINE = 786432
MAX_STDOUT = 2097152
MAX_STDERR = 65536
NETWORK_CODES = frozenset({"EGRESS_HTTP_FAILED", "EGRESS_HTTP_DEADLINE",
                           "EGRESS_DNS_FAILED", "EGRESS_DNS_NO_ADDRESS", "EGRESS_NONPUBLIC_DNS"})


def network_failure_call(denied, loader):
    call = getattr(loader, "awaiting", None)
    if (denied[-1] in NETWORK_CODES and call is not None
            and call["method"] in {"init", "home", "category", "detail", "play"}):
        return {"denialIndex": len(denied) - 1, "method": call["method"]}
    return None


def session_timeout(policy):
    # Multi-hop sources need room for their declared pacing and resolver chain.
    if policy["maxRequests"] <= 24:
        return POLICY["runtimeTimeoutSeconds"]
    return min(180, POLICY["runtimeTimeoutSeconds"]
               + policy["maxRequests"] * policy["minIntervalSeconds"])


def create_arguments(request, hashes):
    args = M.create_arguments(request, hashes["egress_entry.py"], hashes["egress_payload.py"])
    index = args.index(S.MANAGED + ".kind=" + M.KIND)
    args[index] = S.MANAGED + ".kind=" + KIND
    args[-1] = ENTRY
    return args


def owned(document, request):
    normalized = {**document, "Config": {**document.get("Config", {}), "Labels": {
        **(document.get("Config", {}).get("Labels") or {}), S.MANAGED + ".kind": M.KIND}}}
    return ((document.get("Config", {}).get("Labels") or {}).get(S.MANAGED + ".kind") == KIND
            and M.owned(normalized, request))


def validate_created(document, request, hashes):
    S.require(owned(document, request) and document.get("Config", {}).get("Cmd") == ["-I", ENTRY],
              "EGRESS_CONTAINER_CONFIG_MISMATCH")
    normalized = {**document, "Config": {**document["Config"], "Cmd": ["-I", M.ENTRY],
                  "Labels": {**document["Config"]["Labels"], S.MANAGED + ".kind": M.KIND}}}
    M.validate_created(normalized, request, hashes["egress_entry.py"], hashes["egress_payload.py"])


def validate_ready(body, request, hashes):
    value = S.decode(body)
    S.require(isinstance(value, dict) and set(value) == {
        "schemaVersion", "phase", "jobId", "runtimeSha256", "controls", "files"}
        and type(value["schemaVersion"]) is int and value["schemaVersion"] == 1
        and value["phase"] == "ready_before_source" and value["jobId"] == request["jobId"]
        and value["runtimeSha256"] == request["runtimeSha256"]
        and value["files"] == hashes, "EGRESS_READY_MISMATCH")
    try:
        checked = M.E.validate_controls(value["controls"])
        S.require(checked == value["controls"], "CONTAINER_CONTROLS_UNCONFIRMED")
    except (ValueError, KeyError, TypeError):
        raise S.Failure("CONTAINER_CONTROLS_UNCONFIRMED") from None
    return value


def summary(value):
    S.require(isinstance(value, dict) and set(value) == {"kind", "summary"} and value["kind"] == "result"
              and isinstance(value["summary"], dict), "EGRESS_RESULT_INVALID")
    row = value["summary"]
    status = row.get("status")
    result = {"evidenceClass": "untrusted_source_output",
              "status": status if isinstance(status, str) and status in {
                  "init_returned", "home_empty", "category_returned", "detail_returned",
                  "source_exception"} else "invalid_output"}
    for key in ("homeClasses", "homeItems", "homeCovers", "categoryItems", "categoryCovers", "page2Items",
                "detailItems", "detailCovers", "playGroups", "playEpisodes",
                "fixtureChecks", "compatChecks", "dualRuntimeChecks"):
        count = row.get(key)
        result[key] = count if type(count) is int and 0 <= count <= 10000 else None
    for key, allowed, default in (
        ("playerStatus", {"not_sampled", "selection_unavailable", "returned", "empty",
                          "invalid_output", "exception", "policy_denied"}, "invalid_output"),
        ("playerUrlKind", {"absent", "http", "https", "other"}, "other"),
        ("playerParse", {"direct", "parse_required", "invalid"}, "invalid"),
        ("mediaStatus", {"not_requested", "prefix_returned", "http_failed",
                         "policy_denied", "invalid_headers"}, "not_requested"),
    ):
        value = row.get(key)
        result[key] = value if isinstance(value, str) and value in allowed else default
    result["fixturePassed"] = row.get("fixturePassed") is True
    failure = row.get("dualRuntimeFailure")
    result["dualRuntimeFailure"] = failure if failure in {
        "missing_requests", "missing_module_spec", "requirements_uncovered", "other", "none"} else "other"
    markers = row.get("diagnosticMarkers", {})
    result["diagnosticMarkers"] = {
        key: markers.get(key) is True if isinstance(markers, dict) else False
        for key in ("router", "egress", "parse", "decode", "certificate")}
    return result


def exchange(command, request, source, hashes, broker, *, timeout=POLICY["runtimeTimeoutSeconds"],
             cancelled=None, t4_call=None, t4_session=None):
    M.validate_request(request, source)
    M.check_cancelled(cancelled)
    if hasattr(broker, "bind_source"):
        broker.bind_source(source)
    if t4_call is not None:
        try:
            t4_call = copy.deepcopy(t4_call)
            T4Call.validate_call(t4_call)
        except Invalid:
            raise S.Failure("T4_RPC_CALL_INVALID") from None
    env = {key: val for key, val in os.environ.items()
           if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP"}}
    with tempfile.TemporaryDirectory(prefix="at-broker-docker-config-") as config:
        env["DOCKER_CONFIG"] = config
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, env=env)
        condition = threading.Condition()
        pending = deque()
        failures = []
        totals = {"stdoutBytes": 0, "stderrBytes": 0}
        eof = threading.Event()
        deadline = time.monotonic() + timeout
        if hasattr(broker, "bind_execution"):
            broker.bind_execution(deadline, cancelled)
        sent = threading.Event()
        writer_error = threading.Event()
        writer = None
        def terminate():
            try:
                if process.poll() is None:
                    process.kill()
            except OSError:
                pass

        timer = threading.Timer(timeout, terminate)
        timer.daemon = True
        timer.start()

        def read_output():
            partial = bytearray()
            with process.stdout:
                while True:
                    chunk = process.stdout.read1(4096)
                    with condition:
                        if not chunk:
                            eof.set()
                            if partial:
                                failures.append("EGRESS_PROTOCOL_INVALID")
                            condition.notify_all()
                            return
                        totals["stdoutBytes"] += len(chunk)
                        partial.extend(chunk)
                        while b"\n" in partial:
                            line, _, remainder = partial.partition(b"\n")
                            partial = bytearray(remainder)
                            if len(line) > MAX_LINE or len(pending) >= 4:
                                failures.append("ENGINE_OUTPUT_LIMIT")
                                break
                            pending.append(bytes(line))
                        if totals["stdoutBytes"] > MAX_STDOUT or len(partial) > MAX_LINE:
                            failures.append("ENGINE_OUTPUT_LIMIT")
                        condition.notify_all()
                        if failures:
                            process.kill()
                            return

        def read_errors():
            with process.stderr:
                while True:
                    chunk = process.stderr.read1(4096)
                    if not chunk:
                        return
                    with condition:
                        totals["stderrBytes"] += len(chunk)
                        if totals["stderrBytes"] > MAX_STDERR:
                            failures.append("ENGINE_OUTPUT_LIMIT")
                            process.kill()
                            condition.notify_all()
                            return

        def line():
            with condition:
                while not pending:
                    M.check_cancelled(cancelled)
                    S.require(not failures, failures[0] if failures else "EGRESS_PROTOCOL_INVALID")
                    S.require(time.monotonic() < deadline, "ENGINE_TIMEOUT")
                    S.require(not eof.is_set(), "EGRESS_PROTOCOL_INVALID")
                    condition.wait(timeout=0.02)
                S.require(not failures, failures[0] if failures else "EGRESS_PROTOCOL_INVALID")
                return pending.popleft()

        readers = [threading.Thread(target=read_output, daemon=True),
                   threading.Thread(target=read_errors, daemon=True)]
        for reader in readers:
            reader.start()
        phase, last_id, denied, result, loader = "ready", 0, [], None, None
        last_fetch_denied = False
        request_diagnostic = None
        denial_observations = []
        network_failure_calls = []
        try:
            header = {key: val for key, val in request.items() if key != "imageId"}
            process.stdin.write(S.encode(header) + b"\n")
            process.stdin.flush()
            ready = validate_ready(line(), request, hashes)
            phase = "source"
            M.check_cancelled(cancelled)

            def send_source():
                try:
                    process.stdin.write(S.encode({"operation": "execute", "jobId": request["jobId"]}) + b"\n")
                    process.stdin.write(source + b"\n")
                    process.stdin.flush()
                    sent.set()
                except (OSError, ValueError):
                    writer_error.set()

            writer = threading.Thread(target=send_source, daemon=True)
            writer.start()
            S.require(sent.wait(timeout=max(0.01, deadline - time.monotonic()))
                      and not writer_error.is_set(), "EGRESS_SOURCE_SEND_FAILED")
            for _ in range(96):
                M.check_cancelled(cancelled)
                phase = "frame"
                value = S.decode(line())
                S.require(sent.is_set() and not writer_error.is_set(), "EGRESS_SOURCE_SEND_FAILED")
                S.require(isinstance(value, dict), "EGRESS_PROTOCOL_INVALID")
                if "kind" not in value and "id" in value:
                    S.require(loader is not None and loader.awaiting is not None
                              and value.get("id") == str(loader.awaiting["id"])
                              and type(value.get("ok")) is bool
                              and set(value) == {"id", "ok", "result" if value["ok"] else "error"},
                              "EGRESS_PROTOCOL_INVALID")
                    if not value["ok"]:
                        error = value["error"]
                        S.require(isinstance(error, dict) and set(error) == {"code", "message"}
                                  and isinstance(error["code"], str) and len(error["code"]) <= 100
                                  and isinstance(error["message"], str), "EGRESS_PROTOCOL_INVALID")
                    value = {"kind": "call_result", "id": loader.awaiting["id"], "ok": value["ok"],
                             **({"result": value["result"]} if value["ok"] else {
                                 "code": error["code"] if error["code"] in Loader.FAILURE_TYPES
                                 else "LOADER_METHOD_FAILED"})}
                    loader.jsonl_observed = True
                if value.get("kind") in {"loader_available", "loader_source", "call_result"}:
                    phase = "loader"
                    try:
                        if value == {"kind": "loader_available"}:
                            S.require(loader is None, "EGRESS_PROTOCOL_INVALID")
                            loader = (T4Session(source, request["runtimeSha256"], broker, t4_session)
                                      if t4_session is not None else T4Call(source, request["runtimeSha256"], broker, t4_call)
                                      if t4_call is not None else Loader(
                                          source, request["runtimeSha256"], broker,
                                          profile=getattr(broker, "loader_profile", None),
                                          mode=getattr(broker, "loader_mode", "full")))
                            reply = loader.next()
                        else:
                            S.require(loader is not None, "EGRESS_PROTOCOL_INVALID")
                            reply = loader.source_ready(value) if value["kind"] == "loader_source" else loader.accept(value)
                    except Invalid:
                        raise S.Failure("LOADER_RECEIPT_INVALID") from None
                    M.check_cancelled(cancelled)
                    S.require(time.monotonic() < deadline, "ENGINE_TIMEOUT")
                    process.stdin.write(S.encode(reply) + b"\n")
                    process.stdin.flush()
                    continue
                if value.get("kind") == "result":
                    S.require(loader is None or loader.awaiting is None, "EGRESS_PROTOCOL_INVALID")
                    result = summary({"kind": "result", "summary": loader.summary} if loader else value)
                    break
                S.require(set(value) == {"kind", "id", "request"} and value["kind"] == "fetch"
                          and type(value["id"]) is int and value["id"] == last_id + 1
                          and last_id < MAX_REQUESTS,
                          "EGRESS_PROTOCOL_INVALID")
                last_id = value["id"]
                request_diagnostic = None
                last_fetch_denied = False
                phase = "fetch"
                try:
                    response = {"id": last_id, "ok": True, "response": broker.fetch(value["request"])}
                except Denied as error:
                    denied.append(error.code)
                    last_fetch_denied = True
                    request_diagnostic = getattr(error, "request_diagnostic", None)
                    observation = {"httpId": last_id, "code": error.code}
                    if request_diagnostic is not None:
                        observation["requestDiagnostic"] = copy.deepcopy(request_diagnostic)
                    denial_observations.append(observation)
                    response = {"id": last_id, "ok": False, "code": error.code}
                    if error.code == "EGRESS_RATE_LIMITED" and error.retry_after is not None:
                        response["retryAfter"] = error.retry_after
                except (OSError, ValueError, HTTPException):
                    denied.append("EGRESS_HTTP_FAILED")
                    last_fetch_denied = True
                    response = {"id": last_id, "ok": False, "code": "EGRESS_HTTP_FAILED"}
                if last_fetch_denied:
                    associated = network_failure_call(denied, loader)
                    if associated is not None:
                        network_failure_calls.append(associated)
                S.require(time.monotonic() < deadline, "ENGINE_TIMEOUT")
                process.stdin.write(S.encode(response) + b"\n")
                process.stdin.flush()
            S.require(result is not None, "EGRESS_PROTOCOL_INVALID")
            phase = "exit"
            process.stdin.close()
            while process.poll() is None:
                M.check_cancelled(cancelled)
                S.require(time.monotonic() < deadline, "ENGINE_TIMEOUT")
                time.sleep(0.02)
            for reader in readers:
                reader.join(timeout=2)
            M.check_cancelled(cancelled)
            S.require(time.monotonic() < deadline, "ENGINE_TIMEOUT")
            S.require(not failures, failures[0] if failures else "EGRESS_PROTOCOL_INVALID")
            S.require(process.returncode == 0 and not pending, "EGRESS_PROTOCOL_INVALID")
            receipt = {"schemaVersion": 1, "authority": "managed_egress_host_observed",
                    "preSourceReady": ready, "sourceSentAfterControlAck": True,
                    "inputSourceBytes": len(source), "inputSourceSha256": S.digest(source),
                    "untrustedRuntimeSummary": result, "httpObservations": broker.events,
                    "deniedCodes": denied, **totals, "rawOutputStored": False,
                    "businessVerdictTrusted": False, "playbackVerified": False}
            receipt["networkFailureCalls"] = network_failure_calls
            getattr(broker, "snapshot_cooldown", lambda: None)()
            receipt["rateLimitObservations"] = copy.deepcopy(getattr(broker, "rate_limits", []))
            receipt["networkObservations"] = copy.deepcopy(getattr(broker, "network_observations", []))
            receipt["networkObservationsDropped"] = getattr(broker, "network_observations_dropped", 0)
            receipt["dnsObservations"] = copy.deepcopy(getattr(broker, "dns_observations", []))
            receipt["dnsObservationsDropped"] = getattr(broker, "dns_observations_dropped", 0)
            if t4_session is not None:
                S.require(loader is not None, "T4_SESSION_INCOMPLETE")
                receipt["t4SessionValidation"] = loader.receipt()
            elif t4_call is not None:
                S.require(loader is not None, "T4_RPC_INCOMPLETE")
                receipt["t4RpcValidation"] = loader.receipt()
                try:
                    receipt["t4RpcResult"] = loader.take_result()
                except Invalid:
                    raise S.Failure("T4_RPC_INCOMPLETE") from None
            elif loader is not None:
                receipt["loaderValidation"] = loader.receipt()
                if loader.resource_evidence is not None:
                    receipt["resourceEvidence"] = loader.resource_evidence
            return receipt
        except S.Failure as error:
            error.exchange_context = {
                "phase": phase, "lastHttpId": last_id,
                "loaderCallCount": len(getattr(loader, "calls", ())),
                "peerExitCode": process.poll(),
                "policyDenied": bool(denied),
                "lastFetchDenied": last_fetch_denied,
                "lastDeniedCode": denied[-1] if denied else None}
            if request_diagnostic is not None:
                error.exchange_context["requestDiagnostic"] = copy.deepcopy(request_diagnostic)
            if denial_observations:
                error.exchange_context["denialObservations"] = copy.deepcopy(denial_observations)
            redirect = getattr(broker, "last_redirect", None)
            if redirect is not None:
                error.exchange_context["redirectObservation"] = copy.deepcopy(redirect)
            getattr(broker, "snapshot_cooldown", lambda: None)()
            rates = getattr(broker, "rate_limits", [])
            if rates:
                error.exchange_context["rateLimit"] = copy.deepcopy(rates[-1])
            network = getattr(broker, "network_observations", [])
            if network:
                error.exchange_context["networkObservation"] = copy.deepcopy(network[-1])
            dns = getattr(broker, "dns_observations", [])
            if dns:
                error.exchange_context["dnsObservations"] = copy.deepcopy(dns)
                error.exchange_context["dnsObservationsDropped"] = getattr(broker, "dns_observations_dropped", 0)
            for attribute, field in (("last_http_observation", "httpObservation"),
                                     ("last_dns_observation", "dnsObservation")):
                observed = getattr(broker, attribute, None)
                if observed is not None:
                    error.exchange_context[field] = copy.deepcopy(observed)
            error.exchange_context["completedHttpCount"] = len(broker.events)
            error.exchange_context["completedHttpObservations"] = [
                {key: event[key] for key in (
                    "method", "requestUrlSha256", "responseStatus", "responseBytes", "responseSha256",
                    "responseComplete", "responseContentType", "requestCookiePresent",
                    "receivedSetCookieCount") if key in event}
                for event in broker.events[-MAX_REQUESTS:]]
            raise
        except subprocess.TimeoutExpired:
            raise S.Failure("ENGINE_TIMEOUT") from None
        except OSError:
            S.require(time.monotonic() < deadline, "ENGINE_TIMEOUT")
            raise S.Failure("EGRESS_PROTOCOL_INVALID") from None
        finally:
            timer.cancel()
            if process.poll() is None:
                process.kill()
            process.wait(timeout=3)
            if writer:
                writer.join(timeout=3)
            if not process.stdin.closed:
                try:
                    process.stdin.close()
                except OSError:
                    # The exited peer can reject the buffered stream's final flush.
                    pass
            for reader in readers:
                reader.join(timeout=3)
            if (t4_call is not None or t4_session is not None) and loader is not None:
                loader.iterator.close()


def cleanup(engine, request, expected_id=None):
    named = S.container_id(engine, request["jobId"])
    S.require(expected_id is None or named in {None, expected_id}, "CONTAINER_OWNERSHIP_MISMATCH")
    ident = expected_id or named
    uncertain = False
    if named:
        rows = engine.document(["container", "inspect", named])
        S.require(isinstance(rows, list) and len(rows) == 1 and rows[0].get("Id") == named
                  and owned(rows[0], request), "CONTAINER_OWNERSHIP_MISMATCH")
        try:
            engine.call(["container", "rm", "--force", named])
        except S.Failure as error:
            if error.code not in {"ENGINE_TIMEOUT", "ENGINE_COMMAND_FAILED", "ENGINE_STREAM_UNCLOSED"}:
                raise
            uncertain = True
    if ident is not None:
        return M.confirm_cleanup(engine, request, ident, wait=uncertain)
    S.require(S.container_id(engine, request["jobId"]) is None, "CONTAINER_CLEANUP_UNCONFIRMED")
    return {"confirmed": True, "containerAbsent": True}


def run(engine, request, source, hashes, broker, *, cancelled=None, exchange_timeout=None, t4_call=None,
        t4_session=None):
    M.validate_request(request, source)
    M.check_cancelled(cancelled)
    if t4_call is not None:
        try:
            t4_call = copy.deepcopy(t4_call)
            T4Call.validate_call(t4_call)
        except Invalid:
            raise S.Failure("T4_RPC_CALL_INVALID") from None
    info = engine.document(["info", "--format", "{{json .}}"])
    S.require(info.get("OSType") == "linux" and info.get("CgroupVersion") == "2",
              "LINUX_CGROUP_V2_REQUIRED")
    images = engine.document(["image", "inspect", request["imageId"]])
    S.require(len(images) == 1 and images[0].get("Id") == request["imageId"]
              and images[0].get("Os") == "linux" and not images[0].get("Config", {}).get("Volumes"),
              "LOCAL_IMAGE_MISMATCH")
    labels = images[0].get("Config", {}).get("Labels") or {}
    S.require(all(labels.get(S.MANAGED + "." + key.removesuffix(".py").replace("_", "-")) == value
                  for key, value in hashes.items())
              and labels.get(S.MANAGED + ".runtime") == request["runtimeSha256"], "EGRESS_IMAGE_BUNDLE_MISMATCH")
    S.require(S.container_id(engine, request["jobId"]) is None, "JOB_ALREADY_EXISTS")
    confirmed = False
    ident = None
    try:
        ident = engine.call(create_arguments(request, hashes),
                            timeout=M.POLICY["createTimeoutSeconds"]).decode("ascii").strip()
        S.require(re.fullmatch(r"[a-f0-9]{64}", ident), "CONTAINER_CREATE_INVALID")
        confirmed = True
        M.check_cancelled(cancelled)
        rows = engine.document(["container", "inspect", ident])
        S.require(len(rows) == 1 and rows[0].get("Id") == ident, "CONTAINER_INSPECT_INVALID")
        validate_created(rows[0], request, hashes)
        options = {} if exchange_timeout is None else {"timeout": exchange_timeout}
        if t4_call is not None:
            options["t4_call"] = t4_call
        if t4_session is not None:
            options["t4_session"] = t4_session
        result = exchange([*engine.command, "container", "start", "--attach", "--interactive", ident],
                          request, source, hashes, broker, cancelled=cancelled, **options)
        rows = engine.document(["container", "inspect", ident])
        S.require(len(rows) == 1 and rows[0].get("Id") == ident and owned(rows[0], request),
                  "CONTAINER_OWNERSHIP_MISMATCH")
        state = rows[0]["State"]
        S.require(state.get("Status") == "exited" and state.get("ExitCode") == 0
                  and state.get("OOMKilled") is False, "CONTAINER_EXECUTION_FAILED")
        result.update({"containerId": ident, "jobId": request["jobId"], "imageId": request["imageId"],
                       "sourceNetwork": "none", "policy": broker.policy, "containerExitCode": 0})
    finally:
        try:
            cleaned = cleanup(engine, request, ident if confirmed else None)
        except BaseException:
            raise S.Failure("CONTAINER_CLEANUP_UNCONFIRMED") from None
        S.require(confirmed, "CONTAINER_CLEANUP_UNCONFIRMED")
    result["cleanup"] = cleaned
    if "t4RpcValidation" in result:
        result["t4RpcValidation"]["cleanup"] = cleaned
    if "t4SessionValidation" in result:
        result["t4SessionValidation"]["cleanup"] = cleaned
    if "loaderValidation" in result:
        # The method result is not proof that the daemon container was removed.
        result["loaderValidation"] = Loader.cleaned_receipt(result["loaderValidation"], cleaned)
    M.check_cancelled(cancelled)
    return result


def acceptance(engine, request, source, hashes, broker, *, cancelled=None):
    """Sequential workers share one durable job/name; never leave an unjournaled child name."""
    result = run(engine, request, source, hashes, broker, cancelled=cancelled,
                 exchange_timeout=session_timeout(broker.policy))
    receipt = result.get("loaderValidation")
    if receipt is None:
        return result
    required = set(receipt["requiredNodes"])
    rows = {row["id"]: row for row in receipt["nodes"]}
    if any(row["status"] in {"fail", "blocked"} and node not in {"S07", "R09"}
           for node, row in rows.items()):
        return result

    def replace(node, reason, **evidence):
        for values in (receipt["nodes"], receipt["observedNodes"]):
            values[:] = [row for row in values if row["id"] != node]
            values.append({"id": node, "status": "pass", "reason": reason, **evidence})

    def fresh(mode="full"):
        import source_egress
        value = source_egress.mediator(copy.deepcopy(broker.policy))
        value.loader_profile = copy.deepcopy(broker.loader_profile)
        value.loader_mode = mode
        return value

    if "S08" in required and receipt.get("hotHomeCategoriesSha256"):
        cold = run(engine, request, source, hashes, fresh("cold"), cancelled=cancelled)
        compared = cold.get("loaderValidation", {})
        fields = ("inputSourceSha256", "loadedTextSha256", "runtimeSha256", "profileSha256",
                  "hotHomeCategoriesSha256")
        S.require(all(compared.get(key) == receipt.get(key) for key in fields)
                  and cold["containerId"] != result["containerId"]
                  and cold["imageId"] == result["imageId"]
                  and cold["cleanup"] == result["cleanup"] == {"confirmed": True, "containerAbsent": True},
                  "LOADER_COLD_IDENTITY_MISMATCH")
        replace("S08", "FRESH_TMPFS_WORKERS_MATCHED_HOT_HOME",
                workerContainerIds=[result["containerId"], cold["containerId"]],
                hotHomeCategoriesSha256=receipt["hotHomeCategoriesSha256"], persistentCacheConfigured=False)

    if required.intersection({"S07", "R09"}) and rows.get("R09", {}).get("normalCleanupConfirmed"):
        scenarios = []
        for scenario in ("cancel", "timeout", "exit"):
            M.check_cancelled(cancelled)
            mediator = fresh()
            fetch = mediator.fetch
            injected = []

            def controlled_fetch(value):
                response = fetch(value)
                if not injected:
                    S.require(response["status"] in {200, 206}, "LOADER_FAULT_HTTP_REQUIRED")
                    injected.append(True)
                    if scenario == "timeout":
                        time.sleep(5.2)
                    elif scenario == "exit":
                        ident = S.container_id(engine, request["jobId"])
                        documents = engine.document(["container", "inspect", ident])
                        S.require(len(documents) == 1 and documents[0]["Id"] == ident
                                  and owned(documents[0], request), "CONTAINER_OWNERSHIP_MISMATCH")
                        engine.call(["container", "kill", "--signal", "KILL", ident])
                return response

            mediator.fetch = controlled_fetch
            code = None
            try:
                run(engine, request, source, hashes, mediator,
                    cancelled=lambda: bool(cancelled and cancelled()) or scenario == "cancel" and bool(injected),
                    exchange_timeout=5 if scenario == "timeout" else None)
            except S.Failure as error:
                code = error.code
                if code == "CONTAINER_CLEANUP_UNCONFIRMED":
                    raise
            M.check_cancelled(cancelled)
            expected = {"cancel": {"MANAGED_CANCELLED"}, "timeout": {"ENGINE_TIMEOUT"},
                        "exit": {"EGRESS_PROTOCOL_INVALID", "CONTAINER_EXECUTION_FAILED"}}
            S.require(injected and mediator.events and code in expected[scenario],
                      "LOADER_LIFECYCLE_NOT_OBSERVED")
            ident = engine.create_id
            cleaned = M.confirm_cleanup(engine, request, ident)
            scenarios.append({"scenario": scenario, "code": code, "containerId": ident,
                              "httpCount": len(mediator.events), "cleanup": cleaned})
        replace("R09", "NORMAL_CANCEL_TIMEOUT_EXIT_CLEANUP_OBSERVED",
                normalCleanupConfirmed=True, scenarios=scenarios)
        if rows.get("S07", {}).get("protocolChecksPassed"):
            replace("S07", "JSONL_ERRORS_RECOVERY_TIMEOUT_EXIT_OBSERVED",
                    strictFrameParsing=True, protocolChecksPassed=True, rejectedRequests=3,
                    scenarios=scenarios)
    by_id = {row["id"]: row for row in receipt["nodes"]}
    receipt["nodes"] = [by_id[node] for node in receipt["requiredNodes"]]
    receipt["successful"] = receipt.get("requirementsFrozen") is True and all(
        row["status"] == "pass" for row in receipt["nodes"])
    return result
