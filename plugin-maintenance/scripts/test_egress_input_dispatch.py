"""Exercise fixed protocol input routing without executing a source or making HTTP requests."""
from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
import queue
import threading
import unittest


ROOT = Path(__file__).resolve().parents[1] / "managed-source"


def module(name):
    spec = importlib.util.spec_from_file_location("dispatch_test_" + name, ROOT / (name + ".py"))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def frame(value):
    return json.dumps(value, separators=(",", ":")).encode() + b"\n"


class InputDispatchTest(unittest.TestCase):
    def setUp(self):
        self.bridge = module("egress_bridge")
        self.payload = module("egress_payload")
        self.command = {"kind": "call", "id": 4, "method": "category", "params": {"pg": "2"}}
        self.reply = {"id": 19, "ok": True, "response": {"status": 200, "body": ""}}

    def test_background_reply_before_next_command_is_not_a_loader_command(self):
        broker = self.bridge.BrokerInput(io.BytesIO(frame(self.reply) + frame(self.command)))
        worker = self.payload.WorkerInput(self.bridge.CommandInput(broker))
        call = json.loads(next(worker))
        self.assertEqual({key: self.command[key] for key in ("id", "method", "params")}, call)
        self.assertEqual(self.reply, json.loads(broker.readline()))

    def test_next_command_before_background_reply_is_not_an_http_response(self):
        broker = self.bridge.BrokerInput(io.BytesIO(frame(self.command) + frame(self.reply)))
        self.assertEqual(self.reply, json.loads(broker.readline()))
        self.assertEqual(self.command, json.loads(self.bridge.CommandInput(broker).readline(98305)))

    def test_source_handshake_and_finish_use_the_command_channel(self):
        broker = self.bridge.BrokerInput(io.BytesIO(
            frame(self.reply) + frame({"kind": "loader_ack"}) + frame({"kind": "finish"})))
        commands = self.bridge.CommandInput(broker)
        self.assertEqual(b'{"kind":"loader_ack"}\n', commands.readline(128))
        with self.assertRaises(StopIteration):
            next(self.payload.WorkerInput(commands))
        self.assertEqual(self.reply, json.loads(broker.readline()))
        self.assertEqual(b"", broker.readline())

    def test_concurrent_consumers_share_one_physical_reader_without_losing_a_wakeup(self):
        for first in ("command", "response"):
            with self.subTest(first=first):
                incoming, entered = queue.Queue(), threading.Event()

                class Stream:
                    def readline(self, limit):
                        entered.set()
                        return incoming.get(timeout=2)

                broker = self.bridge.BrokerInput(Stream())
                results, errors = {}, []

                def read(channel):
                    try:
                        results[channel] = json.loads(broker.readline(channel=channel))
                    except BaseException as error:
                        errors.append(type(error).__name__)

                second = "response" if first == "command" else "command"
                readers = [threading.Thread(target=read, args=(channel,), daemon=True)
                           for channel in (first, second)]
                readers[0].start()
                self.assertTrue(entered.wait(1))
                readers[1].start()
                incoming.put(frame(self.reply if second == "response" else self.command))
                incoming.put(frame(self.command if first == "command" else self.reply))
                for reader in readers:
                    reader.join(3)
                    self.assertFalse(reader.is_alive())
                self.assertEqual([], errors)
                self.assertEqual({"command": self.command, "response": self.reply}, results)

    def test_invalid_oversized_duplicate_and_partial_frames_stop_both_channels(self):
        invalid = (b"not-json\n", b"[]\n", frame({"kind": "unknown"}),
                   frame({**self.command, "params": {"data": "x" * 98304}}),
                   frame(self.reply) + frame(self.reply), frame(self.reply).rstrip(b"\n"))
        for body in invalid:
            with self.subTest(length=len(body)):
                broker = self.bridge.BrokerInput(io.BytesIO(body))
                with self.assertRaises(ValueError):
                    broker.readline(channel="command")
                with self.assertRaises(ValueError):
                    broker.readline()


if __name__ == "__main__":
    unittest.main()
