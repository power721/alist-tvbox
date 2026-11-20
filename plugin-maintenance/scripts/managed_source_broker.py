"""Lab-only Unix-socket executor. No network listener, credentials, commands or source journal."""
from __future__ import annotations

import argparse
import base64
import copy
import json
import os
from pathlib import Path
import re
import socketserver
import stat
import struct
import threading
import time
import uuid

import managed_source_protocol as M
import build_managed_runtime as B
import managed_egress_protocol as G
import source_egress as H
import managed_http_acceptance as HTTP
import evidence_gateway as EG
from managed_loader import digest as rpc_digest
from managed_loader import SessionChannel, source_identity

S = M.S
MAX_FRAME = 1500000
MAX_REPLY = 65536
MAX_JOURNAL_BYTES = 524288
MAX_STATUS_REPLY = MAX_JOURNAL_BYTES + 1024
STATUS_OPERATIONS = {"status", "cancel", "cancel_acceptance", "start_http"}
MAX_SESSION_REPLY = 786432
UUID = r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
TERMINAL = {"COMPLETED", "FAILED", "CANCELLED", "CLEANUP_UNCONFIRMED"}
SAFE_CODES = frozenset("""
MANAGED_BROKER_FAILED ENGINE_TIMEOUT ENGINE_STREAM_UNCLOSED ENGINE_OUTPUT_LIMIT ENGINE_COMMAND_FAILED
REQUEST_TOO_LARGE JSON_INVALID JSON_DUPLICATE_FIELD LOCAL_ENGINE_REQUIRED
CONTAINER_LOOKUP_INVALID CONTAINER_OWNERSHIP_MISMATCH CONTAINER_CLEANUP_UNCONFIRMED
CONTAINER_CREATE_INVALID CONTAINER_INSPECT_INVALID CONTAINER_IMAGE_MISMATCH CONTAINER_CONFIG_MISMATCH
CONTAINER_ENV_MISMATCH CONTAINER_LIMITS_MISMATCH CONTAINER_ACCESS_MISMATCH CONTAINER_STATE_MISMATCH
CONTAINER_CONTROLS_UNCONFIRMED CONTAINER_EXECUTION_FAILED LINUX_CGROUP_V2_REQUIRED LOCAL_IMAGE_MISMATCH
MANAGED_REQUEST_INVALID MANAGED_SOURCE_IDENTITY_MISMATCH MANAGED_BUNDLE_MISMATCH MANAGED_READY_MISMATCH
MANAGED_CANCELLED MANAGED_SOURCE_SEND_FAILED JOB_ALREADY_EXISTS MANAGED_RUNNER_RECOVERY_REQUIRED
MANAGED_RUNNER_BUSY MANAGED_CONFIGURATION_INVALID MANAGED_JOURNAL_INVALID MANAGED_JOURNAL_LIMIT
MANAGED_RUNNER_IDENTITY_MISMATCH MANAGED_JOB_IDENTITY_MISMATCH MANAGED_JOB_UNKNOWN
MANAGED_THREAD_START_FAILED MANAGED_CLEANUP_UNCONFIRMED MANAGED_JOURNAL_COMMIT_FAILED
MANAGED_FRAME_INVALID MANAGED_FRAME_LIMIT MANAGED_RUNTIME_BUNDLE_CHANGED MANAGED_ROOT_INVALID
MANAGED_SOCKET_ALREADY_EXISTS MANAGED_RECOVERY_INPUT_INVALID MANAGED_RECOVERY_OFFLINE_REQUIRED
MANAGED_RECOVERY_ROW_CHANGED MANAGED_RECOVERY_CONTAINER_REQUIRED MANAGED_RECOVERY_CONTAINER_MISMATCH
MANAGED_RECOVERY_NOT_REQUIRED
MANAGED_ACCEPTANCE_PARENT_INVALID MANAGED_ACCEPTANCE_CANCELLED
EGRESS_POLICY_INVALID EGRESS_CONTAINER_CONFIG_MISMATCH EGRESS_READY_MISMATCH EGRESS_RESULT_INVALID
EGRESS_PROTOCOL_INVALID EGRESS_SOURCE_SEND_FAILED EGRESS_IMAGE_BUNDLE_MISMATCH
LOADER_PROFILE_INVALID LOADER_RECEIPT_INVALID
SOURCE_CREDENTIALS_INVALID
EVIDENCE_GATEWAY_CONFIGURATION_INVALID EVIDENCE_GATEWAY_CLEANUP_UNCONFIRMED
LOADER_COLD_IDENTITY_MISMATCH LOADER_FAULT_HTTP_REQUIRED LOADER_LIFECYCLE_NOT_OBSERVED
T4_RPC_CALL_INVALID T4_RPC_INCOMPLETE T4_RPC_RECEIPT_INVALID T4_RPC_RESULT_LIMIT T4_RPC_RESULT_UNAVAILABLE
T4_HTTP_DISABLED T4_HTTP_ALREADY_STARTED T4_HTTP_DRIVER_FAILED T4_HTTP_RECEIPT_MISMATCH
EGRESS_RATE_LIMITED EGRESS_COOLDOWN_BUDGET EGRESS_CANCELLED
T4_SESSION_FAILED T4_SESSION_INCOMPLETE T4_SESSION_ACTION_UNAVAILABLE T4_SESSION_RESULT_UNAVAILABLE
T4_SESSION_CONTEXT_CHANGED T4_SESSION_EXPIRED T4_SESSION_CANCELLED
T4_SESSION_PROXY_DENIED
""".split())


def valid_uuid(value):
    return isinstance(value, str) and re.fullmatch(UUID, value) is not None


def fixed_code(error):
    code = getattr(error, "code", "")
    return code if isinstance(code, str) and code in SAFE_CODES else "MANAGED_BROKER_FAILED"


def read_exact(stream, count):
    body = bytearray()
    while len(body) < count:
        chunk = stream.read(count - len(body))
        S.require(bool(chunk), "MANAGED_FRAME_INVALID")
        body.extend(chunk)
    return bytes(body)


class TrackedEngine:
    def __init__(self, engine):
        self.engine = engine
        self.command = engine.command
        self.create_issued = False
        self.create_confirmed = False
        self.create_id = None
        self.create_ack_bytes = 0
        self.calls = []
        self.failure_stage = None
        self.primary_code = None
        self.cleanup_code = None
        self.exchange_context = None

    def record_failure(self, stage, error):
        if stage == "cleanup":
            self.cleanup_code = fixed_code(error)
        elif self.primary_code is None:
            self.failure_stage, self.primary_code = stage, fixed_code(error)
            self.exchange_context = getattr(error, "exchange_context", None)

    def diagnostics(self):
        return {"schemaVersion": 1, "authority": "managed_source_broker_observed",
                "failureStage": self.failure_stage, "primaryCode": self.primary_code,
                "cleanupCode": self.cleanup_code, "createIssued": self.create_issued,
                "createConfirmed": self.create_confirmed, "createAckBytes": self.create_ack_bytes,
                "calls": copy.deepcopy(self.calls), "exchangeContext": copy.deepcopy(self.exchange_context)}

    def call(self, arguments, *args, **kwargs):
        create = arguments[:2] == ["container", "create"]
        operation = {
            ("info",): "engine_info", ("image", "inspect"): "image_inspect",
            ("container", "ls"): "container_lookup", ("container", "create"): "container_create",
            ("container", "inspect"): "container_inspect", ("container", "rm"): "container_remove",
        }.get(tuple(arguments[:1] if arguments[0] == "info" else arguments[:2]), "unknown")
        record = {"operation": operation, "outcome": "returned"}
        started = time.monotonic()
        if create:
            self.create_issued = True
            self.create_confirmed, self.create_id, self.create_ack_bytes = False, None, 0
        try:
            result = self.engine.call(arguments, *args, **kwargs)
            if create:
                self.create_ack_bytes = len(result)
                self.create_confirmed = re.fullmatch(rb"[a-f0-9]{64}", result.strip()) is not None
                if self.create_confirmed:
                    self.create_id = result.strip().decode("ascii")
            return result
        except BaseException as error:
            record.update(outcome="failed", code=fixed_code(error))
            # Numeric errno is safe; never retain an exception message, command or Engine output.
            if isinstance(error, OSError) and type(error.errno) is int:
                record["osErrno"] = error.errno
            self.record_failure(operation, error)
            raise
        finally:
            record["elapsedMillis"] = max(0, int((time.monotonic() - started) * 1000))
            self.calls.append(record)
            self.calls = self.calls[-24:]

    def document(self, arguments):
        return S.decode(self.call(arguments))


class Broker:
    def __init__(self, journal, instance_id, image_id, engine_factory, *, execute=M.run, recovery_only=False,
                 egress_policy=None, http_image_id=None, http_driver=HTTP.Driver):
        S.require(valid_uuid(instance_id) and re.fullmatch(r"sha256:[a-f0-9]{64}", image_id),
                  "MANAGED_CONFIGURATION_INVALID")
        self.journal = Path(journal)
        self.instance_id, self.image_id = instance_id, image_id
        self.bundle = B.document()
        self.bundle_sha = S.digest(B.body())
        self.egress_policy = None if egress_policy is None else copy.deepcopy(H.policy_from(egress_policy))
        self.egress_sha = None if self.egress_policy is None else S.digest(S.encode(self.egress_policy))
        S.require(http_image_id is None or self.egress_policy is not None
                  and isinstance(http_image_id, str) and re.fullmatch(r"sha256:[a-f0-9]{64}", http_image_id),
                  "MANAGED_CONFIGURATION_INVALID")
        self.http_image_id, self.http_driver = http_image_id, http_driver
        self.http_threads, self.http_events = {}, {}
        self.http_active = None
        if self.egress_policy is not None:
            S.require({"GET", "HEAD"} <= set(self.egress_policy["methods"]), "EGRESS_POLICY_INVALID")
            execute = self.sample
        self.engine_factory, self.execute = engine_factory, execute
        self.lock = threading.Lock()
        self.jobs, self.events, self.threads = {}, {}, {}
        self.active = None
        # At most one bounded body, invalidated by take/cancel/next start/close, never journaled.
        self.pending_rpc = None
        self.sessions = {}
        self.job_credentials = {}
        self.job_gateways = {}
        # One host-owned continuation; URLs never enter RPC replies or the durable journal.
        self.trusted_routes = self.staged_routes = self.active_route_identity = None
        self.egress_next_request = 0
        self.blocked = False
        self.closed = False
        self.recovery_only = recovery_only
        self.journal.mkdir(mode=0o700, parents=False, exist_ok=True)
        S.require(not self.journal.is_symlink(), "MANAGED_JOURNAL_INVALID")
        # Every production broker holds an OS lock for its entire lifetime (main).
        for path in self.journal.iterdir():
            S.require(path.name == ".lock" or re.fullmatch(UUID + r"\.json", path.name),
                      "MANAGED_JOURNAL_INVALID")
            if path.name == ".lock":
                continue
            S.require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_JOURNAL_BYTES,
                      "MANAGED_JOURNAL_INVALID")
            row = S.decode(path.read_bytes())
            S.require(isinstance(row, dict) and row.get("jobId") == path.stem
                      and row.get("instanceId") == self.instance_id
                      and row.get("imageId") == self.image_id and row.get("bundleSha256") == self.bundle_sha
                      and row.get("egressPolicySha256") == self.egress_sha
                      and row.get("status") in TERMINAL | {"RUNNING"}
                      and ("acceptanceCancelled" not in row
                           or row["acceptanceCancelled"] is True
                           and "parentJobId" not in row and "t4CallSha256" not in row)
                      and re.fullmatch(r"[a-f0-9]{64}", row.get("executionPolicySha256", "")),
                      "MANAGED_JOURNAL_INVALID")
            if row["status"] == "RUNNING" and not recovery_only:
                row.update(status="CLEANUP_UNCONFIRMED", code="MANAGED_RUNNER_RESTARTED",
                           cleanup={"confirmed": False, "containerAbsent": False})
                self.save(row)
            http = row.get("httpAcceptance")
            if http is not None:
                S.require(isinstance(http, dict) and http.get("imageId") == http_image_id
                          and http.get("status") in TERMINAL | {"RUNNING"}
                          and "parentJobId" not in row and "t4CallSha256" not in row,
                          "MANAGED_JOURNAL_INVALID")
                if http["status"] == "RUNNING" and not recovery_only:
                    http.update(status="CLEANUP_UNCONFIRMED", code="MANAGED_RUNNER_RESTARTED",
                                cleanup={"confirmed": False, "containerAbsent": False})
                    self.save(row)
                self.blocked |= http["status"] in {"RUNNING", "CLEANUP_UNCONFIRMED"}
            self.jobs[path.stem] = row
            self.blocked |= row["status"] in {"RUNNING", "CLEANUP_UNCONFIRMED"}
        for row in self.jobs.values():
            if "parentJobId" in row or "parentProfileSha256" in row:
                S.require(valid_uuid(row.get("parentJobId"))
                          and row.get("parentJobId") != row["jobId"]
                          and isinstance(row.get("t4CallSha256"), str)
                          and re.fullmatch(r"[a-f0-9]{64}", row["t4CallSha256"]),
                          "MANAGED_JOURNAL_INVALID")
                S.require(self.acceptance_parent(row) == {
                    key: row.get(key) for key in ("parentJobId", "parentProfileSha256")},
                    "MANAGED_JOURNAL_INVALID")

    def save(self, row, *, exclusive=False):
        body = S.encode(row)
        S.require(len(body) <= MAX_JOURNAL_BYTES, "MANAGED_JOURNAL_LIMIT")
        path = self.journal / (row["jobId"] + ".json")
        temporary = self.journal / (row["jobId"] + ".pending")
        target = path if exclusive else temporary
        with target.open("xb") as stream:
            stream.write(body)
            stream.flush()
            os.fsync(stream.fileno())
        if not exclusive:
            os.replace(temporary, path)
        if os.name == "posix":
            descriptor = os.open(self.journal, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)

    def identity(self):
        value = {"schemaVersion": 1, "authority": "managed_source_broker_configuration",
                "instanceId": self.instance_id, "imageId": self.image_id,
                "bundleSha256": self.bundle_sha, "egressPolicySha256": self.egress_sha,
                "recoveryRequired": self.blocked}
        if self.http_image_id is not None:
            value["httpImageId"] = self.http_image_id
        return value

    def route_identity(self, request, call, execution_sha, parent_job=None):
        if call is None or self.egress_policy is None or self.egress_policy["schemaVersion"] != 3:
            return None
        return (self.instance_id, self.image_id, self.bundle_sha, request["sourceSha256"],
                request["runtimeSha256"], call["playType"], execution_sha, self.egress_sha,
                rpc_digest(call.get("sourceData", {})), parent_job)

    def sample(self, engine, request, source, _entry_sha, _payload_sha, *, cancelled,
               loader_profile=None, profile_sha=None, t4_call=None, t4_session=None):
        mediator = H.mediator(copy.deepcopy(self.egress_policy))
        mediator.loader_profile = copy.deepcopy(loader_profile)
        with self.lock:
            mediator.next_request = self.egress_next_request
            credentials = self.job_credentials.get(request["jobId"])
            if credentials is not None:
                mediator.bind_credentials(credentials)
            gateway_settings = self.job_gateways.get(request["jobId"])
            gateway = EG.RuntimeGateway(S.decode(gateway_settings.encode()), H) if gateway_settings else None
            mediator.runtime_gateway = gateway
            route_identity = self.active_route_identity
            if route_identity is not None and self.trusted_routes is not None:
                S.require(self.trusted_routes[0] == route_identity, "MANAGED_RUNNER_IDENTITY_MISMATCH")
                mediator.restore_urls(self.trusted_routes[1])
        failure = None
        try:
            options = ({"exchange_timeout": G.session_timeout(self.egress_policy)}
                       if self.egress_policy["maxRequests"] > 24 else {})
            if t4_session is not None:
                result = G.run(engine, request, source, self.bundle["egressFiles"], mediator,
                               cancelled=cancelled, t4_session=t4_session, **options)
            elif t4_call is None:
                result = G.acceptance(engine, request, source, self.bundle["egressFiles"], mediator,
                                      cancelled=cancelled)
            else:
                result = G.run(engine, request, source, self.bundle["egressFiles"], mediator,
                               cancelled=cancelled, t4_call=t4_call, **options)
        except S.Failure as error:
            failure = error
            raise
        finally:
            gateway_cleaned = gateway.close() if gateway else True
            if gateway and failure is not None and isinstance(getattr(failure, "exchange_context", None), dict):
                failure.exchange_context["runtimeGateway"] = gateway.summary()
            with self.lock:
                self.egress_next_request = max(self.egress_next_request, mediator.next_request)
            S.require(gateway_cleaned, "EVIDENCE_GATEWAY_CLEANUP_UNCONFIRMED")
        if gateway is not None:
            result["runtimeGateway"] = gateway.summary()
        if route_identity is not None:
            with self.lock:
                self.staged_routes = (request["jobId"], route_identity, mediator.snapshot_urls())
        if "loaderValidation" in result:
            result["loaderValidation"]["inputProfileSha256"] = profile_sha
        result.update(authority="managed_source_host_observed", businessChainVerified=False,
                      executionKind="t4_business_sample", egressPolicySha256=self.egress_sha,
                      untrustedStdoutBytes=result.pop("stdoutBytes"),
                      untrustedStderrBytes=result.pop("stderrBytes"),
                      containerState={"status": "exited", "exitCode": 0, "oomKilled": False})
        return result

    def rpc_projection(self, result, request, source, call):
        """Project trusted transport metadata; no untrusted body or extra field enters save()."""
        S.require(isinstance(result, dict) and isinstance(result.get("t4RpcResult"), dict)
                  and isinstance(result.get("t4RpcValidation"), dict), "T4_RPC_RECEIPT_INVALID")
        body, receipt = result["t4RpcResult"], result["t4RpcValidation"]
        expected = {
            "schemaVersion": 1, "authority": "host_t4_rpc_transport_observed",
            "inputSourceSha256": request["sourceSha256"],
            "loadedTextSha256": S.digest(source.decode("utf-8-sig").encode("utf-8")),
            "runtimeSha256": request["runtimeSha256"], "method": call["method"],
            "playType": call["playType"], "paramsSha256": rpc_digest(call["params"]),
            "resultSha256": rpc_digest(body), "actionReturned": True, "destroyReturned": True,
            "initializationKind": "credential_free_local_source_projection",
            "sourceDataPresent": "sourceData" in call,
            "sourceDataSha256": rpc_digest(call.get("sourceData", {})),
            "fullGeneratedExtForwarded": False, "businessChainVerified": False,
            "productNodePassesAdded": 0, "publishEligible": False, "rawOutputStored": False,
            "cleanup": {"confirmed": True, "containerAbsent": True},
        }
        source_data = call.get("sourceData", {})
        if all(key in source_data for key in ("sourceRuleSha256", "dependencySha256")):
            expected.update(source_identity(source))
        S.require(all(type(receipt.get(key)) is type(value) and receipt[key] == value
                      for key, value in expected.items())
                  and result.get("cleanup") == expected["cleanup"], "T4_RPC_RECEIPT_INVALID")
        delivery = {"schemaVersion": 1, "authority": "host_t4_rpc_transport_observed",
                    "jobId": request["jobId"], "sourceSha256": request["sourceSha256"],
                    "runtimeSha256": request["runtimeSha256"], "t4CallSha256": rpc_digest(call),
                    "resultSha256": expected["resultSha256"], "evidenceClass": "untrusted_source_output",
                    "businessChainVerified": False, "publishEligible": False, "result": body}
        with self.lock:
            row = self.jobs[request["jobId"]]
            if "parentJobId" in row:
                delivery.update({key: row[key] for key in ("parentJobId", "parentProfileSha256")})
        S.require(len(S.encode({"ok": True, "result": delivery})) <= MAX_REPLY, "T4_RPC_RESULT_LIMIT")
        safe = {"schemaVersion": 1, "authority": "managed_source_host_observed",
                "executionKind": "t4_single_action_rpc", "t4RpcValidation": expected,
                "rawOutputStored": False, "businessChainVerified": False, "publishEligible": False}
        return safe, S.encode(delivery)

    def validate(self, value):
        S.require(isinstance(value, dict) and type(value.get("schemaVersion")) is int
                  and value["schemaVersion"] == 1, "MANAGED_REQUEST_INVALID")
        if value.get("operation") == "identity":
            S.require(set(value) == {"schemaVersion", "operation"}, "MANAGED_REQUEST_INVALID")
            return
        common = {"schemaVersion", "operation", "jobId", "instanceId", "imageId",
                  "bundleSha256", "executionPolicySha256"}
        if self.egress_policy is not None:
            common.add("egressPolicySha256")
            S.require(value.get("egressPolicySha256") == self.egress_sha,
                      "MANAGED_RUNNER_IDENTITY_MISMATCH")
        operation = value.get("operation")
        start_fields = {"sourceBase64", "sourceSha256"}
        if self.egress_policy is not None:
            start_fields.add("t4CallJson" if "t4CallJson" in value else "loaderProfileJson")
            if "t4CallJson" in value and "parentJobId" in value:
                start_fields.add("parentJobId")
        extra = start_fields if operation in {"start", "start_session"} else (
            {"sourceBase64", "sourceSha256", "loaderProfileJson"} if operation == "start_http" else (
            {"sourceSha256", "t4CallSha256"} if operation == "take" else (
            {"ordinal", "t4CallJson"} if operation == "session_call" else (
            {"ordinal", "t4CallSha256"} if operation == "session_take" else set())))
        )
        if operation == "start" and "parentJobId" not in value and "hostCredentials" in value:
            S.require(self.egress_policy is not None, "MANAGED_REQUEST_INVALID")
            try:
                H.validate_host_credentials(value["hostCredentials"])
            except H.Denied:
                raise S.Failure("SOURCE_CREDENTIALS_INVALID") from None
            extra = extra | {"hostCredentials"}
        if operation in {"start", "start_session"} and "parentJobId" not in value and "evidenceGatewayJson" in value:
            S.require(self.egress_policy is not None, "MANAGED_REQUEST_INVALID")
            try:
                encoded = value["evidenceGatewayJson"]
                S.require(isinstance(encoded, str) and 0 < len(encoded.encode()) <= 4096,
                          "EVIDENCE_GATEWAY_CONFIGURATION_INVALID")
                EG.runtime_settings(S.decode(encoded.encode()))
            except (EG.GatewayError, ValueError):
                raise S.Failure("EVIDENCE_GATEWAY_CONFIGURATION_INVALID") from None
            extra = extra | {"evidenceGatewayJson"}
        S.require(operation in {"start", "start_session", "session_call", "session_take", "session_close",
                                "start_http", "status", "cancel", "cancel_acceptance", "take"}
                  and set(value) == common | extra
                  and valid_uuid(value.get("jobId")), "MANAGED_REQUEST_INVALID")
        if operation == "start_session":
            S.require(self.egress_policy is not None and "t4CallJson" in value, "MANAGED_REQUEST_INVALID")
        if operation in {"start", "start_session"} and "parentJobId" in value:
            S.require(valid_uuid(value["parentJobId"]) and value["parentJobId"] != value["jobId"],
                      "MANAGED_ACCEPTANCE_PARENT_INVALID")
        if operation == "take":
            S.require(self.egress_policy is not None
                      and all(isinstance(value[key], str) and re.fullmatch(r"[a-f0-9]{64}", value[key])
                              for key in ("sourceSha256", "t4CallSha256")), "MANAGED_REQUEST_INVALID")
        S.require(value["instanceId"] == self.instance_id and value["imageId"] == self.image_id
                  and value["bundleSha256"] == self.bundle_sha, "MANAGED_RUNNER_IDENTITY_MISMATCH")
        S.require(isinstance(value["executionPolicySha256"], str)
                  and re.fullmatch(r"[a-f0-9]{64}", value["executionPolicySha256"]),
                  "MANAGED_REQUEST_INVALID")

    def acceptance_parent(self, value):
        """Bind a child to a completed loader execution, not to a caller-supplied verdict."""
        if "parentJobId" not in value:
            return {}
        parent = self.jobs.get(value["parentJobId"], {})
        result = parent.get("result", {})
        loader = result.get("loaderValidation", {}) if isinstance(result, dict) else {}
        profile_sha = parent.get("loaderProfileSha256")
        S.require(parent.get("status") == "COMPLETED" and "parentJobId" not in parent
                  and "t4CallSha256" not in parent
                  and parent.get("executionPolicySha256") == value["executionPolicySha256"]
                  and parent.get("sourceSha256") == value["sourceSha256"]
                  and isinstance(profile_sha, str) and re.fullmatch(r"[a-f0-9]{64}", profile_sha)
                  and parent.get("cleanup") == {
                      "confirmed": True, "containerAbsent": True,
                      "basis": "create_ack_exact_id_absent_and_worker_exited"}
                  and isinstance(result, dict) and result.get("authority") == "managed_source_host_observed"
                  and result.get("executionKind") == "t4_business_sample"
                  and isinstance(loader, dict) and loader.get("authority") == "host_loader_contract_observed"
                  and loader.get("scope") == G.Loader.SCOPE and loader.get("requirementsFrozen") is True
                  and loader.get("inputProfileSha256") == profile_sha
                  and loader.get("inputSourceSha256") == value["sourceSha256"]
                  and loader.get("runtimeSha256") == self.bundle["runtimeSha256"]
                  and isinstance(loader.get("requiredNodes"), list) and "R04" in loader["requiredNodes"]
                  and isinstance(loader.get("nodes"), list)
                  and any(isinstance(node, dict) and node.get("id") == "S06"
                          and node.get("status") == "pass" for node in loader["nodes"]),
                  "MANAGED_ACCEPTANCE_PARENT_INVALID")
        return {"parentJobId": value["parentJobId"], "parentProfileSha256": profile_sha}

    def project_row(self, row):
        projected = copy.deepcopy(row)
        if row.get("acceptanceCancelled") is True:
            children = [child for child in self.jobs.values() if child.get("parentJobId") == row["jobId"]]
            related = [row, *children]
            pending = sum(item["status"] == "RUNNING"
                          or item["jobId"] in self.threads and self.threads[item["jobId"]].is_alive()
                          for item in related)
            cleaned = all(item.get("cleanup", {}).get("confirmed") is True
                          and item.get("cleanup", {}).get("containerAbsent") is True for item in related)
            recovery = self.blocked or any(item["status"] == "CLEANUP_UNCONFIRMED" for item in related)
            http = row.get("httpAcceptance")
            if http is not None:
                pending += int(http["status"] == "RUNNING"
                               or row["jobId"] in self.http_threads
                               and self.http_threads[row["jobId"]].is_alive())
                cleaned &= http.get("cleanup") == {"confirmed": True, "containerAbsent": True}
                recovery |= http["status"] == "CLEANUP_UNCONFIRMED"
            projected["acceptanceCleanup"] = {
                "confirmed": cleaned and not pending and not recovery,
                "childJobs": len(children) + int(http is not None), "pendingJobs": pending,
                "recoveryRequired": recovery}
        return projected

    def start_http(self, value, parent):
        """Under the same admission lock; the controller does not occupy a source execution slot."""
        S.require(self.http_image_id is not None, "T4_HTTP_DISABLED")
        S.require(not self.closed and not self.blocked and not self.recovery_only,
                  "MANAGED_RUNNER_RECOVERY_REQUIRED")
        binding = self.acceptance_parent({**value, "parentJobId": value["jobId"]})
        S.require(parent is not None and parent.get("acceptanceCancelled") is not True,
                  "MANAGED_ACCEPTANCE_CANCELLED")
        S.require(self.active is None and self.http_active is None, "MANAGED_RUNNER_BUSY")
        S.require("httpAcceptance" not in parent, "T4_HTTP_ALREADY_STARTED")
        S.require(isinstance(value.get("sourceBase64"), str), "MANAGED_SOURCE_IDENTITY_MISMATCH")
        try:
            source = base64.b64decode(value["sourceBase64"], validate=True)
        except (ValueError, TypeError):
            raise S.Failure("MANAGED_SOURCE_IDENTITY_MISMATCH") from None
        S.require(0 < len(source) <= 1048576 and S.digest(source) == parent["sourceSha256"],
                  "MANAGED_SOURCE_IDENTITY_MISMATCH")
        encoded = value["loaderProfileJson"]
        S.require(isinstance(encoded, str) and 0 < len(encoded.encode()) <= 16384
                  and S.digest(encoded.encode()) == binding["parentProfileSha256"],
                  "MANAGED_ACCEPTANCE_PARENT_INVALID")
        job = value["jobId"]
        plan = {key: parent[key] for key in (
            "instanceId", "imageId", "bundleSha256", "executionPolicySha256", "egressPolicySha256",
            "sourceSha256")}
        plan.update(parentJobId=job, sourceImageId=self.image_id, runtimeSha256=self.bundle["runtimeSha256"],
                    loaderProfileJson=encoded, sourceBase64=value["sourceBase64"])
        http = {"schemaVersion": 1, "authority": "managed_t4_http_acceptance_observed",
                "imageId": self.http_image_id, "status": "RUNNING",
                "cleanup": {"confirmed": False, "containerAbsent": False},
                "businessChainVerified": False, "publishEligible": False}
        parent["httpAcceptance"] = http
        try:
            self.save(parent)
        except BaseException:
            self.blocked = True
            raise S.Failure("MANAGED_JOURNAL_COMMIT_FAILED") from None
        self.http_active = job
        self.http_events[job] = threading.Event()
        thread = threading.Thread(target=self.work_http, args=(job, plan), daemon=False)
        self.http_threads[job] = thread
        try:
            thread.start()
        except RuntimeError:
            self.http_threads.pop(job)
            http.update(status="FAILED", code="MANAGED_THREAD_START_FAILED",
                        cleanup={"confirmed": True, "containerAbsent": True})
            try:
                self.save(parent)
            except BaseException:
                self.blocked = True
                raise S.Failure("MANAGED_JOURNAL_COMMIT_FAILED") from None
            finally:
                self.http_active = None
        return self.project_row(parent)

    def work_http(self, job, plan):
        driver = None
        result, code = None, None
        driver_failure = None
        cleanup = {"confirmed": False, "containerAbsent": False}
        try:
            driver = self.http_driver(self.engine_factory(), self.http_image_id,
                                      self.journal.parent / "executor.sock", job)
            result = driver.run(plan, self.http_events[job].is_set)
        except BaseException as error:
            code = fixed_code(error)
            if isinstance(error, HTTP.DriverFailure):
                driver_failure = error.diagnostic
        finally:
            if driver is not None:
                cleanup = copy.deepcopy(driver.cleanup)
            with self.lock:
                parent = self.jobs[job]
                if code or self.http_events[job].is_set() or self.closed:
                    try:
                        self.cancel_acceptance(parent, parent)
                    except BaseException:
                        code = "MANAGED_JOURNAL_COMMIT_FAILED"
                workers = [self.threads[row["jobId"]] for row in self.jobs.values()
                           if row.get("parentJobId") == job and row["jobId"] in self.threads]
            for worker in workers:
                worker.join(timeout=90)
            with self.lock:
                parent = self.jobs[job]
                http = parent["httpAcceptance"]
                http["cleanup"] = cleanup
                if driver is not None and driver.ident is not None:
                    http["containerId"] = driver.ident
                children = {row["jobId"]: row for row in self.jobs.values() if row.get("parentJobId") == job}
                if cleanup != {"confirmed": True, "containerAbsent": True} or any(
                        worker.is_alive() for worker in workers) or any(
                            row.get("cleanup", {}).get("confirmed") is not True
                            or row.get("cleanup", {}).get("containerAbsent") is not True
                            for row in children.values()):
                    http.update(status="CLEANUP_UNCONFIRMED", code="MANAGED_CLEANUP_UNCONFIRMED")
                    self.blocked = True
                elif code and code != "MANAGED_CANCELLED":
                    http.update(status="FAILED", code=code)
                    if code == "T4_HTTP_DRIVER_FAILED":
                        if driver_failure is not None:
                            http["driverFailure"] = driver_failure
                        failure = self.http_request_failure(parent, children)
                        if failure is not None:
                            http["sourceRequestFailure"] = failure
                elif self.http_events[job].is_set() or parent.get("acceptanceCancelled") is True:
                    http.update(status="CANCELLED", code="MANAGED_CANCELLED")
                else:
                    try:
                        binding = {key: parent[key] for key in (
                            "jobId", "instanceId", "imageId", "bundleSha256", "executionPolicySha256",
                            "egressPolicySha256", "sourceSha256")}
                        binding.update(runtimeSha256=self.bundle["runtimeSha256"],
                                       inputProfileSha256=parent["loaderProfileSha256"])
                        profile = S.decode(plan["loaderProfileJson"].encode())
                        data = profile.get("sourceConfig", {})
                        binding.update(sourceDataPresent=bool(data), sourceDataSha256=rpc_digest(data))
                        S.require(all(row["jobId"] not in self.threads
                                      or not self.threads[row["jobId"]].is_alive()
                                      for row in children.values()), "T4_HTTP_RECEIPT_MISMATCH")
                        observed = HTTP.verify_observation(result, binding, children)
                        http.update(status="COMPLETED", observation=observed,
                                    currentChildJournalsVerified=True)
                    except BaseException:
                        http.update(status="FAILED", code="T4_HTTP_RECEIPT_MISMATCH")
                if http["status"] != "COMPLETED":
                    try:
                        self.cancel_acceptance(parent, parent)
                    except BaseException:
                        http.update(status="CLEANUP_UNCONFIRMED", code="MANAGED_JOURNAL_COMMIT_FAILED")
                        self.blocked = True
                try:
                    self.save(parent)
                except BaseException:
                    http.pop("observation", None)
                    http.pop("driverFailure", None)
                    http.pop("sourceRequestFailure", None)
                    http.update(status="CLEANUP_UNCONFIRMED", code="MANAGED_JOURNAL_COMMIT_FAILED")
                    self.blocked = True
                self.http_active = None

    def http_request_failure(self, parent, children):
        """Promote only an exact host-observed mismatch from this parent's durable child."""
        for child in children.values():
            context = child.get("diagnostics", {}).get("exchangeContext") or {}
            diagnostic = context.get("requestDiagnostic") or {}
            if (child.get("status") != "FAILED" or child.get("code") != "EGRESS_PROTOCOL_INVALID"
                    or context.get("policyDenied") is not True
                    or context.get("lastDeniedCode") != "EGRESS_URL_DENIED"
                    or type(context.get("lastHttpId")) is not int
                    or not 1 <= context["lastHttpId"] <= H.MAX_REQUESTS
                    or set(diagnostic) != {"method", "requestUrlSha256", "registeredUrlSha256",
                                           "registeredQuerySeparatorMissing"}
                    or diagnostic.get("method") != "GET"
                    or diagnostic.get("registeredQuerySeparatorMissing") is not True
                    or not all(HTTP.sha(diagnostic.get(key)) for key in (
                        "requestUrlSha256", "registeredUrlSha256"))
                    or diagnostic["requestUrlSha256"] == diagnostic["registeredUrlSha256"]
                    or child.get("parentJobId") != parent["jobId"]
                    or child.get("parentProfileSha256") != parent["loaderProfileSha256"]
                    or any(child.get(key) != parent.get(key) for key in (
                        "instanceId", "imageId", "bundleSha256", "executionPolicySha256",
                        "egressPolicySha256", "sourceSha256"))
                    or child.get("cleanup", {}).get("confirmed") is not True
                    or child.get("cleanup", {}).get("containerAbsent") is not True):
                continue
            path = self.journal / (child["jobId"] + ".json")
            try:
                if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_JOURNAL_BYTES:
                    continue
                raw = path.read_bytes()
                if S.decode(raw) != child:
                    continue
            except (OSError, S.Failure):
                continue
            failure = {key: parent[key] for key in (
                "jobId", "instanceId", "imageId", "bundleSha256", "executionPolicySha256",
                "egressPolicySha256", "sourceSha256")}
            failure.update(schemaVersion=1, authority="host_registered_request_mismatch",
                           inputProfileSha256=parent["loaderProfileSha256"],
                           childJobId=child["jobId"], childJournalSha256=S.digest(raw),
                           currentChildJournalVerified=True, httpId=context["lastHttpId"],
                           cleanup={"confirmed": True, "containerAbsent": True}, **diagnostic)
            return failure
        return None

    def cancel_acceptance(self, value, previous):
        """Called under the admission lock; persist intent without rewriting execution history."""
        job = value["jobId"]
        S.require(previous is None or "parentJobId" not in previous and "t4CallSha256" not in previous,
                  "MANAGED_ACCEPTANCE_PARENT_INVALID")
        row = previous
        if row is None:
            row = self.row(value, "CANCELLED")
            row["cleanup"] = {"confirmed": True, "containerAbsent": True,
                              "basis": "durable_no_start_tombstone"}
            self.jobs[job] = row
        failure = None
        if row.get("acceptanceCancelled") is not True:
            row["acceptanceCancelled"] = True
            try:
                self.save(row, exclusive=previous is None)
            except BaseException:
                self.blocked = True
                failure = S.Failure("MANAGED_JOURNAL_COMMIT_FAILED")
        affected = {job, *(child["jobId"] for child in self.jobs.values()
                          if child.get("parentJobId") == job)}
        for ident in affected:
            self.job_credentials.pop(ident, None)
            self.job_gateways.pop(ident, None)
            if ident in self.events:
                self.events[ident].set()
            if ident in self.sessions:
                self.sessions[ident].close()
        if job in self.http_events:
            self.http_events[job].set()
        if self.pending_rpc is not None and self.pending_rpc[0] in affected:
            self.pending_rpc = None
        if self.trusted_routes is not None and self.trusted_routes[0][-1] == job:
            self.trusted_routes = None
        if self.staged_routes is not None and self.staged_routes[0] in affected:
            self.staged_routes = None
        if failure is not None:
            raise failure
        return self.project_row(row)

    def handle(self, value):
        self.validate(value)
        with self.lock:
            if value["operation"] == "identity":
                return self.identity()
            job = value["jobId"]
            previous = self.jobs.get(job)
            if previous:
                S.require(previous["executionPolicySha256"] == value["executionPolicySha256"],
                          "MANAGED_JOB_IDENTITY_MISMATCH")
            if value["operation"] == "status":
                S.require(previous is not None, "MANAGED_JOB_UNKNOWN")
                return self.project_row(previous)
            if value["operation"] == "cancel_acceptance":
                return self.cancel_acceptance(value, previous)
            if value["operation"] == "start_http":
                return self.start_http(value, previous)
            if value["operation"] in {"session_call", "session_take", "session_close"}:
                S.require(not self.closed and previous is not None and previous["status"] == "RUNNING"
                          and job in self.sessions and not self.events[job].is_set(),
                          "T4_SESSION_ACTION_UNAVAILABLE")
                session = self.sessions[job]
                try:
                    if value["operation"] == "session_close":
                        session.close(require_idle=True)
                        return self.project_row(previous)
                    if value["operation"] == "session_take":
                        return session.receive(value["ordinal"], value["t4CallSha256"])
                    encoded = value["t4CallJson"]
                    S.require(isinstance(encoded, str) and len(encoded.encode()) <= 32768,
                              "T4_RPC_CALL_INVALID")
                    session.submit(value["ordinal"], S.decode(encoded.encode()))
                    return {"accepted": True, "ordinal": value["ordinal"]}
                except G.Invalid as error:
                    raise S.Failure(str(error)) from None
            if value["operation"] == "take":
                S.require(previous is not None, "MANAGED_JOB_UNKNOWN")
                S.require(all(value[key] == previous.get(key) for key in ("sourceSha256", "t4CallSha256")),
                          "MANAGED_JOB_IDENTITY_MISMATCH")
                S.require(not self.jobs.get(previous.get("parentJobId"), {}).get("acceptanceCancelled"),
                          "MANAGED_ACCEPTANCE_CANCELLED")
                S.require(not self.closed and previous["status"] == "COMPLETED"
                          and self.pending_rpc is not None and self.pending_rpc[0] == job,
                          "T4_RPC_RESULT_UNAVAILABLE")
                _, encoded = self.pending_rpc
                # Claim before transport: a lost reply must not replay a source result.
                self.pending_rpc = None
                return S.decode(encoded)
            if value["operation"] == "cancel":
                if self.pending_rpc is not None and self.pending_rpc[0] == job:
                    self.pending_rpc = None
                if previous is None:
                    # Durable tombstone fences a start whose transport is still arriving.
                    previous = self.row(value, "CANCELLED")
                    previous["cleanup"] = {"confirmed": True, "containerAbsent": True,
                                           "basis": "durable_no_start_tombstone"}
                    self.save(previous, exclusive=True)
                    self.jobs[job] = previous
                elif previous["status"] == "RUNNING":
                    self.events[job].set()
                    if job in self.sessions:
                        self.sessions[job].close()
                    self.trusted_routes = self.staged_routes = None
                return self.project_row(previous)
            S.require(previous is None, "JOB_ALREADY_EXISTS")
            S.require(not self.closed and not self.blocked and not self.recovery_only,
                      "MANAGED_RUNNER_RECOVERY_REQUIRED")
            parent_binding = self.acceptance_parent(value)
            S.require(not self.jobs.get(parent_binding.get("parentJobId"), {}).get("acceptanceCancelled"),
                      "MANAGED_ACCEPTANCE_CANCELLED")
            parent_http = self.jobs.get(parent_binding.get("parentJobId"), {}).get("httpAcceptance")
            S.require(parent_http is None or parent_http.get("status") == "RUNNING"
                      and self.http_active == parent_binding.get("parentJobId"),
                      "MANAGED_ACCEPTANCE_CANCELLED")
            S.require(self.active is None, "MANAGED_RUNNER_BUSY")
            S.require(self.http_active is None or parent_binding.get("parentJobId") == self.http_active,
                      "MANAGED_RUNNER_BUSY")
            S.require(isinstance(value.get("sourceBase64"), str), "MANAGED_SOURCE_IDENTITY_MISMATCH")
            try:
                source = base64.b64decode(value["sourceBase64"], validate=True)
            except (ValueError, TypeError):
                raise S.Failure("MANAGED_SOURCE_IDENTITY_MISMATCH") from None
            request = {"schemaVersion": 1, "jobId": job, "imageId": self.image_id,
                       "sourceBytes": len(source), "sourceSha256": value["sourceSha256"],
                       "runtimeSha256": self.bundle["runtimeSha256"]}
            M.validate_request(request, source)
            profile, profile_sha, t4_call = None, None, None
            if "t4CallJson" in value:
                encoded = value["t4CallJson"]
                S.require(isinstance(encoded, str) and 0 < len(encoded.encode("utf-8")) <= 32768,
                          "T4_RPC_CALL_INVALID")
                t4_call = S.decode(encoded.encode("utf-8"))
                try:
                    G.T4Call.validate_call(t4_call)
                except G.Invalid:
                    raise S.Failure("T4_RPC_CALL_INVALID") from None
                if parent_binding:
                    loader = self.jobs[parent_binding["parentJobId"]]["result"]["loaderValidation"]
                    S.require(type(loader.get("sourceDataPresent")) is bool
                              and loader["sourceDataPresent"] == ("sourceData" in t4_call)
                              and loader.get("sourceDataSha256") == rpc_digest(t4_call.get("sourceData", {})),
                              "T4_RPC_CALL_INVALID")
            elif self.egress_policy is not None:
                encoded = value.get("loaderProfileJson")
                S.require(isinstance(encoded, str) and 0 < len(encoded.encode("utf-8")) <= 16384,
                          "LOADER_PROFILE_INVALID")
                profile = S.decode(encoded.encode("utf-8"))
                try:
                    G.Loader.validate_profile(profile)
                except G.Invalid:
                    raise S.Failure("LOADER_PROFILE_INVALID") from None
                S.require(profile.get("runtime") == "t4" and profile.get("extFingerprint") is None,
                          "LOADER_PROFILE_INVALID")
                profile_sha = S.digest(encoded.encode("utf-8"))
            row = self.row(value, "RUNNING")
            credentials = (value.get("hostCredentials") if not parent_binding else
                           self.job_credentials.get(parent_binding["parentJobId"]))
            gateway_settings = (value.get("evidenceGatewayJson") if not parent_binding else
                                self.job_gateways.get(parent_binding["parentJobId"]))
            row.update(parent_binding)
            if gateway_settings is not None:
                row["evidenceGatewaySettingsSha256"] = S.digest(gateway_settings.encode())
            row.update(sourceSha256=request["sourceSha256"], sourceBytes=len(source))
            if profile_sha is not None:
                row["loaderProfileSha256"] = profile_sha
            if t4_call is not None:
                row["t4CallSha256"] = rpc_digest(t4_call)
            session_mode = value["operation"] == "start_session"
            if session_mode:
                row["executionKind"] = "t4_bounded_session"
            self.save(row, exclusive=True)
            if credentials is not None:
                self.job_credentials[job] = copy.deepcopy(credentials)
            if gateway_settings is not None:
                self.job_gateways[job] = gateway_settings
            self.pending_rpc = None
            self.active_route_identity = self.route_identity(
                request, t4_call, value["executionPolicySha256"], value.get("parentJobId"))
            if self.trusted_routes is not None and self.trusted_routes[0] != self.active_route_identity:
                self.trusted_routes = None
            self.staged_routes = None
            self.jobs[job], self.events[job], self.active = row, threading.Event(), job
            if session_mode:
                self.sessions[job] = SessionChannel(t4_call, self.events[job].is_set,
                                                    lambda observed, body, targets:
                                                    self.publish_session(job, observed, body, targets),
                                                    timeout=G.session_timeout(self.egress_policy) - 5)
            thread = threading.Thread(target=self.work, args=(request, source, profile, profile_sha, t4_call),
                                      daemon=False)
            self.threads[job] = thread
            try:
                thread.start()
            except RuntimeError:
                self.job_credentials.pop(job, None)
                self.job_gateways.pop(job, None)
                self.trusted_routes = self.staged_routes = self.active_route_identity = None
                self.threads.pop(job)
                session = self.sessions.pop(job, None)
                if session is not None:
                    session.close(failed=True)
                row.update(status="FAILED", code="MANAGED_THREAD_START_FAILED",
                           cleanup={"confirmed": True, "containerAbsent": True, "basis": "no_worker_started"})
                self.active = None
                try:
                    self.save(row)
                except BaseException:
                    self.blocked = True
                    raise
            return copy.deepcopy(row)

    def publish_session(self, job, observed, body, manifest_targets):
        """Persist safe action identity before exposing an in-memory result; cleanup is still pending."""
        with self.lock:
            row = self.jobs[job]
            S.require(not self.closed and not self.events[job].is_set(), "T4_SESSION_CANCELLED")
            delivery = {"ready": True, "schemaVersion": 1, "authority": "host_t4_session_observed",
                        "jobId": job, "sourceSha256": row["sourceSha256"],
                        "runtimeSha256": self.bundle["runtimeSha256"], **observed,
                        "cleanupPending": True, "businessChainVerified": False,
                        "publishEligible": False, "resultJson": json.dumps(
                            body, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)}
            if manifest_targets is not None:
                # Host-derived capabilities travel once with this exact reply, never in the journal.
                delivery["proxyManifestTargets"] = manifest_targets
            if "parentJobId" in row:
                delivery.update({key: row[key] for key in ("parentJobId", "parentProfileSha256")})
            S.require(len(S.encode({"ok": True, "result": delivery})) <= MAX_SESSION_REPLY, "T4_RPC_RESULT_LIMIT")
            row.setdefault("sessionActions", []).append(copy.deepcopy(observed))
            try:
                self.save(row)
            except BaseException:
                self.blocked = True
                self.events[job].set()
                raise S.Failure("MANAGED_JOURNAL_COMMIT_FAILED") from None
            self.sessions[job].finish(delivery)

    def row(self, value, status):
        row = {"schemaVersion": 1, "authority": "managed_source_broker_observed",
                "instanceId": self.instance_id, "imageId": self.image_id, "bundleSha256": self.bundle_sha,
                "jobId": value["jobId"], "executionPolicySha256": value["executionPolicySha256"],
                "status": status, "businessChainVerified": False, "publishEligible": False}
        if self.egress_sha is not None:
            row["egressPolicySha256"] = self.egress_sha
        return row

    def work(self, request, source, profile=None, profile_sha=None, t4_call=None):
        job = request["jobId"]
        engine = None
        result, code, cleanup = None, None, {"confirmed": False, "containerAbsent": False}
        delivery = None
        try:
            engine = TrackedEngine(self.engine_factory())
            options = {"loader_profile": profile, "profile_sha": profile_sha} if self.egress_policy else {}
            if t4_call is not None:
                options["t4_session" if job in self.sessions else "t4_call"] = (
                    self.sessions[job] if job in self.sessions else t4_call)
            result = self.execute(engine, request, source, self.bundle["entrySha256"],
                                  self.bundle["payloadSha256"], cancelled=self.events[job].is_set, **options)
            if job in self.sessions:
                result = {"schemaVersion": 1, "authority": "managed_source_host_observed",
                          "executionKind": "t4_bounded_session",
                          "t4SessionValidation": result["t4SessionValidation"], "rawOutputStored": False,
                          "businessChainVerified": False, "publishEligible": False}
            elif t4_call is not None:
                result, delivery = self.rpc_projection(result, request, source, t4_call)
        except BaseException as error:
            code = fixed_code(error)
            if engine is not None:
                engine.record_failure("execution", error)
        finally:
            try:
                if code == "JOB_ALREADY_EXISTS":
                    cleanup = {"confirmed": False, "containerAbsent": False}
                elif engine is None or not engine.create_issued:
                    cleanup = {"confirmed": True, "containerAbsent": True, "basis": "no_create_issued"}
                elif engine.create_confirmed:
                    cleanup = {**M.confirm_cleanup(engine, request, engine.create_id),
                               "basis": "create_ack_exact_id_absent_and_worker_exited"}
                # A lost create acknowledgement remains ambiguous, even when lookup is empty.
            except BaseException as error:
                cleanup = {"confirmed": False, "containerAbsent": False}
                if engine is not None:
                    engine.record_failure("cleanup", error)
            with self.lock:
                row = self.jobs[job]
                if not cleanup["confirmed"]:
                    row.update(status="CLEANUP_UNCONFIRMED", code="MANAGED_CLEANUP_UNCONFIRMED")
                    self.blocked = True
                elif self.events[job].is_set():
                    row.update(status="CANCELLED", code="MANAGED_CANCELLED")
                elif code:
                    row.update(status="FAILED", code=code)
                else:
                    row.update(status="COMPLETED", result=result)
                row["cleanup"] = cleanup
                if engine is not None:
                    row["diagnostics"] = engine.diagnostics()
                try:
                    self.save(row)
                except BaseException:
                    row.pop("result", None)
                    row.update(status="CLEANUP_UNCONFIRMED", code="MANAGED_JOURNAL_COMMIT_FAILED",
                               cleanup={"confirmed": False, "containerAbsent": False})
                    self.blocked = True
                if delivery is not None and row["status"] == "COMPLETED":
                    self.pending_rpc = (job, delivery)
                if (row["status"] == "COMPLETED" and self.staged_routes is not None
                        and self.staged_routes[0] == job and not self.closed):
                    self.trusted_routes = self.staged_routes[1:]
                else:
                    self.trusted_routes = None
                self.staged_routes = self.active_route_identity = None
                self.active = None
                session = self.sessions.pop(job, None)
                if session is not None:
                    session.close(failed=code is not None)

    def recover_owned(self, job, expected_sha, ident):
        """Offline operator action under the lifetime OS lock; never called over the socket."""
        S.require(self.recovery_only and self.active is None and not self.threads,
                  "MANAGED_RECOVERY_OFFLINE_REQUIRED")
        S.require(valid_uuid(job) and isinstance(expected_sha, str)
                  and re.fullmatch(r"[a-f0-9]{64}", expected_sha)
                  and isinstance(ident, str) and re.fullmatch(r"[a-f0-9]{64}", ident),
                  "MANAGED_RECOVERY_INPUT_INVALID")
        row = self.jobs.get(job)
        S.require(row is not None and row["status"] in {"RUNNING", "CLEANUP_UNCONFIRMED"},
                  "MANAGED_RECOVERY_NOT_REQUIRED")
        path = self.journal / (job + ".json")
        S.require(S.digest(path.read_bytes()) == expected_sha, "MANAGED_RECOVERY_ROW_CHANGED")
        request = {"jobId": job, "imageId": self.image_id, "runtimeSha256": self.bundle["runtimeSha256"]}
        engine = TrackedEngine(self.engine_factory())
        # An empty lookup cannot resolve an outstanding daemon create. Require the exact extant ID.
        S.require(S.container_id(engine, job) == ident, "MANAGED_RECOVERY_CONTAINER_REQUIRED")
        rows = engine.document(["container", "inspect", ident])
        S.require(isinstance(rows, list) and len(rows) == 1 and rows[0].get("Id") == ident
                  and rows[0].get("State", {}).get("Status") in {"created", "running", "exited"},
                  "MANAGED_RECOVERY_CONTAINER_MISMATCH")
        document = rows[0]
        checked = copy.deepcopy(document)
        checked["State"]["Status"] = "created"
        if self.egress_policy is None:
            M.validate_created(checked, request, self.bundle["entrySha256"], self.bundle["payloadSha256"])
        else:
            G.validate_created(checked, request, self.bundle["egressFiles"])
        S.require(S.digest(path.read_bytes()) == expected_sha, "MANAGED_RECOVERY_ROW_CHANGED")
        cleanup = (M.cleanup if self.egress_policy is None else G.cleanup)(engine, request, ident)
        updated = copy.deepcopy(row)
        updated.pop("result", None)
        updated.update(status="FAILED", code="MANAGED_OPERATOR_RECOVERED",
                       cleanup={**cleanup, "basis": "offline_owned_container_removed"})
        updated["recovery"] = {"schemaVersion": 1, "authority": "offline_operator_observed",
                               "previousRowSha256": expected_sha, "previousStatus": row["status"],
                               "previousCode": row.get("code"), "containerId": ident,
                               "observedState": document["State"]["Status"],
                               "sourceReplayed": False, "journalReset": False}
        self.save(updated)
        self.jobs[job] = updated
        self.blocked = any(item["status"] in {"RUNNING", "CLEANUP_UNCONFIRMED"}
                           or item.get("httpAcceptance", {}).get("status") in {"RUNNING", "CLEANUP_UNCONFIRMED"}
                           for item in self.jobs.values())
        return copy.deepcopy(updated)

    def recover_http_owned(self, job, expected_sha, ident):
        """Offline HTTP-driver recovery, preserving the parent loader and failed history."""
        S.require(self.recovery_only and self.active is None and self.http_active is None
                  and not self.threads and not self.http_threads, "MANAGED_RECOVERY_OFFLINE_REQUIRED")
        S.require(valid_uuid(job) and isinstance(expected_sha, str)
                  and re.fullmatch(r"[a-f0-9]{64}", expected_sha)
                  and isinstance(ident, str) and re.fullmatch(r"[a-f0-9]{64}", ident),
                  "MANAGED_RECOVERY_INPUT_INVALID")
        row = self.jobs.get(job)
        http = row.get("httpAcceptance", {}) if row is not None else {}
        S.require(http.get("status") in {"RUNNING", "CLEANUP_UNCONFIRMED"}
                  and self.http_image_id is not None and http.get("imageId") == self.http_image_id,
                  "MANAGED_RECOVERY_NOT_REQUIRED")
        path = self.journal / (job + ".json")
        S.require(S.digest(path.read_bytes()) == expected_sha, "MANAGED_RECOVERY_ROW_CHANGED")
        bindings = (http.get("containerId"), http.get("recoveryIntent", {}).get("containerId"))
        S.require(all(value in {None, ident} for value in bindings), "MANAGED_RECOVERY_CONTAINER_MISMATCH")
        bound = next((value for value in bindings if value is not None), None)
        driver = HTTP.Driver(TrackedEngine(self.engine_factory()), self.http_image_id,
                             self.journal.parent / "executor.sock", job)
        driver.ident = ident
        named = driver.lookup()
        by_id = driver.engine.call(["container", "ls", "--all", "--no-trunc", "--filter",
                                    "id=" + ident, "--format", "{{.ID}}"]).decode("ascii").strip()
        S.require(named in {None, ident} and by_id in {"", ident},
                  "MANAGED_RECOVERY_CONTAINER_MISMATCH")
        present = named == ident and by_id == ident
        S.require(present or named is None and not by_id and bound == ident,
                  "MANAGED_RECOVERY_CONTAINER_REQUIRED")
        state = "absent"
        if present:
            documents = driver.engine.document(["container", "inspect", ident])
            S.require(isinstance(documents, list) and len(documents) == 1,
                      "MANAGED_RECOVERY_CONTAINER_MISMATCH")
            driver.validate(documents[0])
            state = documents[0].get("State", {}).get("Status")
            S.require(state in {"created", "running", "exited"}, "MANAGED_RECOVERY_CONTAINER_MISMATCH")
        S.require(S.digest(path.read_bytes()) == expected_sha, "MANAGED_RECOVERY_ROW_CHANGED")
        updated = copy.deepcopy(row)
        phase = updated["httpAcceptance"]
        # Commit a fixed ID before removal, so a lost remove/commit reply remains reconcilable.
        phase["recoveryIntent"] = {"containerId": ident, "previousRowSha256": expected_sha}
        updated["acceptanceCancelled"] = True
        self.save(updated)
        self.jobs[job] = updated
        self.blocked = True
        if present:
            driver.issued = True
            driver.remove()
        else:
            driver.confirm_absent()
        completed = copy.deepcopy(updated)
        phase = completed["httpAcceptance"]
        phase.pop("observation", None)
        phase.pop("currentChildJournalsVerified", None)
        phase.update(status="FAILED", code="MANAGED_HTTP_OPERATOR_RECOVERED", cleanup=driver.cleanup,
                     recovery={"schemaVersion": 1, "authority": "offline_operator_observed",
                               "previousRowSha256": expected_sha, "previousStatus": http["status"],
                               "containerId": ident, "observedState": state,
                               "sourceReplayed": False, "journalReset": False})
        self.save(completed)
        self.jobs[job] = completed
        self.blocked = any(item["status"] in {"RUNNING", "CLEANUP_UNCONFIRMED"}
                           or item.get("httpAcceptance", {}).get("status") in {"RUNNING", "CLEANUP_UNCONFIRMED"}
                           for item in self.jobs.values())
        return copy.deepcopy(completed)

    def close(self):
        with self.lock:
            self.closed = True
            self.job_credentials.clear()
            self.job_gateways.clear()
            self.pending_rpc = None
            for session in self.sessions.values():
                session.close()
            self.trusted_routes = self.staged_routes = None
            for event in self.events.values():
                event.set()
            for event in self.http_events.values():
                event.set()
        for thread in [*self.http_threads.values(), *self.threads.values()]:
            thread.join(timeout=90)
            S.require(not thread.is_alive(), "MANAGED_CLEANUP_UNCONFIRMED")


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(8)
        try:
            count = struct.unpack("!I", read_exact(self.rfile, 4))[0]
            S.require(0 < count <= MAX_FRAME, "MANAGED_FRAME_LIMIT")
            value = S.decode(read_exact(self.rfile, count))
            result = {"ok": True, "result": self.server.broker.handle(value)}
        except (S.Failure, OSError, ValueError, TypeError, KeyError) as error:
            result = {"ok": False, "code": fixed_code(error)}
        body = S.encode(result)
        limit = MAX_SESSION_REPLY if (result.get("ok") is True
                and value.get("operation") == "session_take") else MAX_REPLY
        # Combined loader and HTTP receipts exceed a single RPC's body budget.
        if result.get("ok") is True and value.get("operation") in STATUS_OPERATIONS:
            limit = MAX_STATUS_REPLY
        if len(body) > limit:
            body = S.encode({"ok": False, "code": "MANAGED_FRAME_LIMIT"})
        self.wfile.write(struct.pack("!I", len(body)) + body)


class Server(socketserver.ThreadingMixIn, getattr(socketserver, "UnixStreamServer", socketserver.BaseServer)):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, path, broker):
        self.broker = broker
        self.capacity = threading.BoundedSemaphore(8)
        super().__init__(str(path), Handler)

    def process_request(self, request, client_address):
        if not self.capacity.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.capacity.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.capacity.release()


def main():
    import fcntl
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--instance-id", required=True)
    parser.add_argument("--image-id", required=True)
    recovery_mode = parser.add_mutually_exclusive_group()
    recovery_mode.add_argument("--recover-job")
    recovery_mode.add_argument("--recover-http-job")
    parser.add_argument("--container-id")
    parser.add_argument("--journal-sha256")
    parser.add_argument("--egress-policy", type=Path)
    parser.add_argument("--http-image-id")
    args = parser.parse_args()
    recovery_job = args.recover_job or args.recover_http_job
    recovery = any(value is not None for value in (recovery_job, args.container_id, args.journal_sha256))
    S.require(not recovery or all(value is not None for value in (
        recovery_job, args.container_id, args.journal_sha256)), "MANAGED_RECOVERY_INPUT_INVALID")
    root = args.root
    S.require(os.name == "posix" and os.getuid() == 0 and root.is_absolute()
              and root.is_dir() and root.resolve() == root and root.stat().st_uid == 0
              and stat.S_IMODE(root.stat().st_mode) == 0o711, "MANAGED_ROOT_INVALID")
    expected = B.OUTPUT.read_bytes()
    S.require(expected == B.body(), "MANAGED_RUNTIME_BUNDLE_CHANGED")
    journal = root / "journal"
    journal.mkdir(mode=0o700, exist_ok=True)
    S.require(not journal.is_symlink() and journal.stat().st_uid == 0
              and stat.S_IMODE(journal.stat().st_mode) == 0o700, "MANAGED_JOURNAL_INVALID")
    with (journal / ".lock").open("a+b") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise S.Failure("MANAGED_RECOVERY_OFFLINE_REQUIRED") from None
        policy = None
        if args.egress_policy is not None:
            path = args.egress_policy
            S.require(path.is_absolute() and path.resolve() == path and not path.is_symlink()
                      and path.is_file() and path.stat().st_uid == 0 and path.stat().st_size <= 8192
                      and not stat.S_IMODE(path.stat().st_mode) & 0o022, "EGRESS_POLICY_INVALID")
            policy = S.decode(path.read_bytes())
        broker = Broker(journal, args.instance_id, args.image_id,
                        lambda: S.Docker(["/usr/bin/docker"], "unix:///var/run/docker.sock"),
                        recovery_only=recovery, egress_policy=policy, http_image_id=args.http_image_id)
        if recovery:
            recover = broker.recover_http_owned if args.recover_http_job else broker.recover_owned
            print(S.encode(recover(recovery_job, args.journal_sha256, args.container_id)).decode("ascii"))
            return
        endpoint = root / "executor.sock"
        # Never unlink a stale socket automatically: a live mount may still reference its inode.
        S.require(not endpoint.exists() and not endpoint.is_symlink(), "MANAGED_SOCKET_ALREADY_EXISTS")
        with Server(endpoint, broker) as server:
            os.chown(endpoint, 65532, 65532)
            os.chmod(endpoint, 0o600)
            try:
                server.serve_forever(poll_interval=0.1)
            finally:
                broker.close()


if __name__ == "__main__":
    try:
        main()
    except BaseException as error:
        raise SystemExit(fixed_code(error)) from None
