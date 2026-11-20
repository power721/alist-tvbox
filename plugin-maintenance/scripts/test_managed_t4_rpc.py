"""RPC protocol checks with a trusted child, never execute the submitted source."""
import base64
import ast
import copy
import hashlib
import json
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import managed_egress_protocol as G
from managed_loader import Invalid, T4Call, digest
import test_managed_source_protocol as F


CALLS = (
    {"method": "home", "params": {}, "playType": "t4"},
    {"method": "category", "params": {"t": "cat", "pg": "2", "extend": {"note": "a+b & c"}},
     "playType": "t4"},
    {"method": "detail", "params": {"ids": "item#+&"}, "playType": "t4"},
    {"method": "search", "params": {"wd": "\u6d4b\u8bd5 + &", "quick": True, "pg": "3"},
     "playType": "t4"},
    {"method": "play", "params": {"play": "fixture://episode?a=b+c&n=1", "flag": "line + &"},
     "playType": "t4"},
)
HASHES = {"egress_entry.py": "1" * 64, "egress_payload.py": "2" * 64, "egress_bridge.py": "3" * 64}


class Broker:
    policy = {}

    def __init__(self):
        self.events = []

    def fetch(self, value):
        raise AssertionError("Unexpected fixture egress")


class Engine(F.Engine):
    """Inspect/cleanup double only; the protocol peer is a separate trusted process."""
    def call(self, arguments, payload=b"", timeout=6, limit=196608):
        if arguments[:2] == ["image", "inspect"]:
            return G.S.encode([{"Id": F.F.IMAGE, "Os": "linux", "Config": {"Labels": {
                **{G.S.MANAGED + "." + key.removesuffix(".py").replace("_", "-"): value
                   for key, value in HASHES.items()},
                G.S.MANAGED + ".runtime": F.request()["runtimeSha256"]}}}])
        value = super().call(arguments, payload, timeout, limit)
        if arguments[:2] == ["container", "create"]:
            self.container["Config"]["Cmd"] = ["-I", G.ENTRY]
        return value


def ready():
    return {key: value for key, value in F.ready().items()
            if key not in {"entrySha256", "payloadSha256"}} | {"files": HASHES}


def child(defect="", source_data=None):
    prefix = (
        "import base64,hashlib,json,sys,time\n"
        "header=json.loads(sys.stdin.buffer.readline())\n"
        "print(" + repr(json.dumps(ready())) + ",flush=True)\n"
        "ack=json.loads(sys.stdin.buffer.readline())\n"
        "source=sys.stdin.buffer.read(header['sourceBytes']+1)[:-1]\n"
        "assert hashlib.sha256(source).hexdigest()==header['sourceSha256']\n"
        "emit=lambda value: print(json.dumps(value),flush=True)\n"
        "emit({'kind':'loader_available'})\n"
    )
    if defect == "no-loader":
        return prefix.split("emit({'kind'")[0] + "emit({'kind':'result','summary':{'status':'init_returned'}})\n"
    return prefix + (
        "count=0\n"
        "for line in sys.stdin.buffer:\n"
        " call=json.loads(line)\n"
        " if call=={'kind':'finish'}: break\n"
        " count+=1\n"
        " assert count<=3\n"
        " method=call['method']\n"
        " if method=='init':\n"
        "  ext=json.loads(base64.b64decode(call['params']['extend'],validate=True))\n"
        "  expected_data=" + repr(source_data) + "\n"
        "  assert set(ext)==({'source','raw','t4_play_type'} | ({'data'} if expected_data else set()))\n"
        "  assert ext.get('data')==expected_data\n"
        "  assert ext['source']=='/work/managed-source.py' and ext['raw'] is True\n"
        "  assert ext['t4_play_type'] in {'t4','t4-direct'}\n"
        "  if " + repr(defect != "no-source") + ":\n"
        "   sha=hashlib.sha256(source.decode('utf-8-sig').encode()).hexdigest()\n"
        "   emit({'kind':'loader_source','sourceTextSha256':sha})\n"
        "   assert json.loads(sys.stdin.buffer.readline())=={'kind':'loader_ack'}\n"
        "  result={'name': '' if " + repr(defect == "init") + " else 'trusted-fixture','result':None}\n"
        " elif method=='destroy':\n"
        "  result={'status':'wrong' if " + repr(defect == "destroy") + " else 'closed'}\n"
        " else:\n"
        "  if " + repr(defect == "error") + ":\n"
        "   emit({'id':str(call['id']),'ok':False,'error':{'code':'PRIVATE_ERROR','message':'PRIVATE_SECRET'}})\n"
        "   continue\n"
        "  result=[] if " + repr(defect == "scalar") + " else {'transportFixtureOnly':True,'params':call['params']}\n"
        " ident='replayed' if " + repr(defect == "id") + " else str(call['id'])\n"
        " emit({'id':ident,'ok':True,'result':result})\n"
        "emit({'kind':'result','summary':{'status':'init_returned'}})\n"
        + ("time.sleep(5)\n" if defect == "exit-wait" else "")
    )


class RpcTest(unittest.TestCase):
    def setUp(self):
        supervisor = patch.object(G, "S", G.M.S)
        supervisor.start()
        self.addCleanup(supervisor.stop)

    def exchange(self, call=None, defect="", timeout=2):
        call = copy.deepcopy(CALLS[0] if call is None else call)
        return G.exchange([sys.executable, "-I", "-c", child(defect, call.get("sourceData"))],
                          F.request(), F.SOURCE, HASHES, Broker(),
                          t4_call=call, timeout=timeout)

    def run_rpc(self, engine, *, defect="", cancelled=None, timeout=2, call=None):
        transport = G.exchange

        def attached(command, *args, **kwargs):
            self.assertEqual([*engine.command, "container", "start", "--attach", "--interactive", F.F.ID], command)
            result = transport([sys.executable, "-I", "-c", child(defect)], *args, **kwargs)
            engine.container["State"] = {"Status": "exited", "ExitCode": 0, "OOMKilled": False}
            return result

        with patch.object(G, "exchange", side_effect=attached):
            return G.run(engine, F.request(), F.SOURCE, HASHES, Broker(), cancelled=cancelled,
                         exchange_timeout=timeout, t4_call=CALLS[0] if call is None else call)

    def test_five_actions_both_modes_keep_jsonl_hashes_and_no_generated_credentials(self):
        for original in CALLS:
            for mode in ("t4", "t4-direct"):
                call = {**original, "playType": mode}
                with self.subTest(method=call["method"], mode=mode):
                    receipt = self.exchange(call)
                    row = receipt["t4RpcValidation"]
                    self.assertEqual(["init", call["method"], "destroy"], [v["method"] for v in row["calls"]])
                    self.assertEqual(call["params"], receipt["t4RpcResult"]["params"])
                    self.assertEqual(digest(call["params"]), row["paramsSha256"])
                    self.assertEqual(digest(receipt["t4RpcResult"]), row["resultSha256"])
                    self.assertTrue(row["actionReturned"] and row["destroyReturned"])
                    self.assertFalse(row["businessChainVerified"])
                    self.assertEqual(0, row["productNodePassesAdded"])
                    self.assertFalse(row["fullGeneratedExtForwarded"])
                    self.assertFalse(row["sourceDataPresent"])
                    self.assertEqual(digest({}), row["sourceDataSha256"])
                    self.assertNotIn("loaderValidation", receipt)
                    self.assertNotIn("params", row)
                    self.assertNotIn("t4RpcResult", row)
                    self.assertNotIn("cleanup", row)

    def test_approved_public_data_reaches_init_without_a_second_ext_or_persisted_raw_values(self):
        data = {"label": "\u6d4b\u8bd5 + & \U0001f600", "page_size": "20",
                "gateway_timeout": 30, "gateway_retries": 0, "subtitle_enabled": False}
        for mode in ("t4", "t4-direct"):
            receipt = self.exchange({**CALLS[0], "playType": mode, "sourceData": data})
            row = receipt["t4RpcValidation"]
            self.assertTrue(row["sourceDataPresent"])
            self.assertEqual(digest(data), row["sourceDataSha256"])
            self.assertNotIn(data["label"], json.dumps(row, ensure_ascii=False))
            self.assertFalse(row["businessChainVerified"])
        call = {**CALLS[0], "sourceData": copy.deepcopy(data)}
        driver = T4Call(F.SOURCE, F.request()["runtimeSha256"], None, call)
        call["sourceData"]["label"] = "late mutation"
        payload = json.loads(base64.b64decode(driver.next()["params"]["extend"], validate=True))
        self.assertEqual(data, payload["data"])
        # Extract the pinned trusted owner's actual composition method, not a reimplementation.
        path = Path(__file__).resolve().parents[1] / "server/src/main/resources/worker/runtime/Atvp.py"
        tree = ast.parse(path.read_text(encoding="utf-8"))
        owner = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "Spider")
        method = next(node for node in owner.body
                      if isinstance(node, ast.FunctionDef) and node.name == "_compose_inner_extend")
        namespace = {"json": json}
        exec(compile(ast.Module(body=[method], type_ignores=[]), str(path), "exec"), namespace)
        inner = namespace["_compose_inner_extend"](None, payload)
        self.assertEqual(data, json.loads(inner))
        driver.iterator.close()

    def test_invalid_calls_cannot_start_a_process_or_issue_an_engine_command(self):
        invalid = [
            {}, {"method": "port", "params": {}, "playType": "t4"},
            {**CALLS[0], "token": "PRIVATE"},
            {**CALLS[0], "playType": []}, {**CALLS[0], "params": []},
            {**CALLS[0], "params": {"filter": 1}},
            {**CALLS[1], "params": {"t": "cat", "pg": 2}},
            {**CALLS[1], "params": {"t": "cat", "pg": "0"}},
            {**CALLS[1], "params": {"t": "cat", "pg": "10001"}},
            {**CALLS[1], "params": {"t": "cat", "pg": "\uff12"}},
            {**CALLS[1], "params": {"t": "cat", "pg": "2", "extend": {"x": 1}}},
            {**CALLS[2], "params": {"ids": ""}},
            {**CALLS[3], "params": {"wd": "word", "quick": "true", "pg": "1"}},
            {**CALLS[4], "params": {**CALLS[4]["params"], "proxy_base": "PRIVATE"}},
            {**CALLS[0], "params": {"extend": "PRIVATE"}},
        ]
        for data in (None, {}, [], "raw", {"label": 1}, {"label": {"nested": "x"}},
                     {"gateway_timeout": True}, {"gateway_timeout": 30.5},
                     {"gateway_timeout": -1}, {"gateway_timeout": 100001},
                     {"subtitle_enabled": 0}, {"subtitle_enabled": "false"},
                     {"label": ["x"]}, {"label": "x" * 1025}, {"label": "a\nb"},
                     {"label": "\ud800"}, {"label": "\x7f"}, {"\u6d4b\u8bd5": "x"},
                     {str(i): "x" for i in range(33)},
                     {"k" + str(i): "x" * 1024 for i in range(9)},
                     *({key: "PRIVATE"} for key in (
                         "api", "source", "loader", "raw", "data", "extend", "token",
                         "Local_Proxy_Config", "proxy_base", "secret", "password", "cookie",
                         "Authorization", "api_key", "access_token", "preheatUrl", "t4_play_type"))):
            invalid.append({**CALLS[0], "sourceData": data})
        for call in invalid:
            with self.subTest(call=call), patch.object(G.subprocess, "Popen") as popen:
                with self.assertRaisesRegex(G.S.Failure, "T4_RPC_CALL_INVALID"):
                    self.exchange(call)
                popen.assert_not_called()
                engine = unittest.mock.Mock()
                with self.assertRaisesRegex(G.S.Failure, "T4_RPC_CALL_INVALID"):
                    G.run(engine, F.request(), F.SOURCE, HASHES, None, t4_call=call)
                engine.assert_not_called()
                self.assertEqual([], engine.mock_calls)

    def test_rpc_errors_missing_source_init_and_destroy_cannot_return_a_result(self):
        for defect in ("error", "no-source", "init", "destroy", "scalar", "no-loader"):
            with self.subTest(defect=defect):
                with self.assertRaisesRegex(G.S.Failure, "T4_RPC_INCOMPLETE") as error:
                    self.exchange(defect=defect)
                self.assertNotIn("PRIVATE", str(error.exception))

    def test_rpc_reply_id_cannot_be_replayed_or_forged(self):
        with self.assertRaisesRegex(G.S.Failure, "EGRESS_PROTOCOL_INVALID"):
            self.exchange(defect="id")

    def test_result_is_not_released_until_the_peer_has_exited(self):
        with self.assertRaisesRegex(G.S.Failure, "ENGINE_TIMEOUT"):
            self.exchange(defect="exit-wait", timeout=0.2)

    def test_configuration_is_copied_and_transient_result_is_one_shot(self):
        call = copy.deepcopy(CALLS[3])
        driver = T4Call(F.SOURCE, F.request()["runtimeSha256"], None, call)
        call["params"]["wd"] = "mutated"
        init = driver.next()
        driver.source_ready({"kind": "loader_source",
                             "sourceTextSha256": hashlib.sha256(F.SOURCE.decode("utf-8-sig").encode()).hexdigest()})
        action = driver.accept({"kind": "call_result", "id": init["id"], "ok": True,
                                "result": {"name": "trusted-fixture"}})
        self.assertEqual(CALLS[3]["params"], action["params"])
        destroy = driver.accept({"kind": "call_result", "id": action["id"], "ok": True, "result": {"list": []}})
        driver.accept({"kind": "call_result", "id": destroy["id"], "ok": True, "result": {"status": "closed"}})
        driver.jsonl_observed = True
        expected_sha = driver.receipt()["resultSha256"]
        self.assertEqual({"list": []}, driver.take_result())
        self.assertEqual(expected_sha, driver.receipt()["resultSha256"])
        with self.assertRaises(Invalid):
            driver.take_result()

    def test_abandoned_generator_closes_without_issuing_destroy_or_releasing_result(self):
        for phase in ("init", "action", "destroy"):
            with self.subTest(phase=phase):
                driver = T4Call(F.SOURCE, F.request()["runtimeSha256"], Broker(), CALLS[0])
                call = driver.next()
                driver.source_ready({"kind": "loader_source", "sourceTextSha256": driver.text_sha})
                if phase != "init":
                    call = driver.accept({"kind": "call_result", "id": call["id"], "ok": True,
                                          "result": {"name": "trusted-fixture"}})
                if phase == "destroy":
                    driver.accept({"kind": "call_result", "id": call["id"], "ok": True, "result": {}})
                before = copy.deepcopy(driver.calls)
                driver.iterator.close()
                self.assertEqual(before, driver.calls)
                with self.assertRaisesRegex(Invalid, "T4_RPC_INCOMPLETE"):
                    driver.take_result()

    def test_runner_result_follows_exit_and_confirmed_cleanup(self):
        engine = Engine()
        original = engine.call
        removed = []

        def called(arguments, *args, **kwargs):
            if arguments[:2] == ["container", "rm"]:
                self.assertEqual("exited", engine.container["State"]["Status"])
                removed.append(True)
            return original(arguments, *args, **kwargs)

        engine.call = called
        result = self.run_rpc(engine)
        self.assertEqual([True], removed)
        self.assertIsNone(engine.container)
        self.assertEqual({"confirmed": True, "containerAbsent": True}, result["cleanup"])
        self.assertEqual(result["cleanup"], result["t4RpcValidation"]["cleanup"])
        self.assertTrue(result["t4RpcResult"]["transportFixtureOnly"])
        self.assertEqual(0, result["t4RpcValidation"]["productNodePassesAdded"])

    def test_runner_failure_and_timeout_clean_the_owned_container_and_reap_peer(self):
        popen = G.subprocess.Popen
        processes = []

        def started(*args, **kwargs):
            process = popen(*args, **kwargs)
            processes.append(process)
            return process

        for defect, code in (("error", "T4_RPC_INCOMPLETE"), ("id", "EGRESS_PROTOCOL_INVALID"),
                             ("exit-wait", "ENGINE_TIMEOUT")):
            engine = Engine()
            with self.subTest(defect=defect), patch.object(G.subprocess, "Popen", side_effect=started):
                with self.assertRaisesRegex(G.S.Failure, code):
                    self.run_rpc(engine, defect=defect, timeout=0.4)
                self.assertIsNone(engine.container)
        self.assertEqual(3, len(processes))
        for process in processes:
            self.assertIsNotNone(process.returncode)
            self.assertTrue(all(stream.closed for stream in (process.stdin, process.stdout, process.stderr)))

    def test_unconfirmed_cleanup_cannot_deliver_an_otherwise_valid_result(self):
        engine = Engine()
        engine.refuse_remove = True
        with self.assertRaisesRegex(G.S.Failure, "CONTAINER_CLEANUP_UNCONFIRMED"):
            self.run_rpc(engine)
        self.assertIsNotNone(engine.container)

    def test_cancellation_during_cleanup_discards_late_result(self):
        engine = Engine()
        original = engine.call
        cancelled = threading.Event()

        def called(arguments, *args, **kwargs):
            result = original(arguments, *args, **kwargs)
            if arguments[:2] == ["container", "rm"]:
                cancelled.set()
            return result

        engine.call = called
        with self.assertRaisesRegex(G.S.Failure, "MANAGED_CANCELLED"):
            self.run_rpc(engine, cancelled=cancelled.is_set)
        self.assertIsNone(engine.container)

    def test_cancelled_pipe_reaps_peer_before_returning(self):
        cancelled = threading.Event()
        timer = threading.Timer(0.1, cancelled.set)
        started = time.monotonic()
        timer.start()
        try:
            with self.assertRaisesRegex(G.S.Failure, "MANAGED_CANCELLED"):
                G.exchange([sys.executable, "-I", "-c", child("exit-wait")], F.request(), F.SOURCE,
                           HASHES, Broker(), t4_call=CALLS[0], cancelled=cancelled.is_set, timeout=5)
        finally:
            timer.cancel()
            timer.join(timeout=1)
        self.assertLess(time.monotonic() - started, 3)

    def test_runner_freezes_call_before_engine_commands(self):
        engine = Engine()
        original = engine.document
        call = copy.deepcopy(CALLS[3])

        def document(arguments):
            call["params"]["wd"] = "changed-after-start"
            return original(arguments)

        engine.document = document
        result = self.run_rpc(engine, call=call)
        self.assertEqual(CALLS[3]["params"], result["t4RpcResult"]["params"])


if __name__ == "__main__":
    unittest.main()
