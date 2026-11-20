"""Fixed T4 business/player sample. All source-derived results remain untrusted."""
from __future__ import annotations

import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import runpy
import sys
from urllib.parse import urlsplit, parse_qsl, urlencode

ROOT = Path("/opt/at-maintenance")
MAX_MANIFEST_BYTES = 524288
MAX_IMAGE_BYTES = 524288


class ObservedErrors:
    def __init__(self, target):
        self.target = target
        self.sample = ""

    def write(self, text):
        self.sample = (self.sample + str(text))[:8192]
        return self.target.write(text)

    def flush(self):
        self.target.flush()


def rows(value):
    if isinstance(value, str):
        value = json.loads(value)
    if not isinstance(value, dict) or not isinstance(value.get("list"), list):
        raise ValueError("BUSINESS_LIST_INVALID")
    return value, value["list"]


def sample_player(worker, detail, result):
    import egress_bridge
    import requests

    result.update(playerStatus="selection_unavailable", playerUrlKind="absent",
                  playerParse="invalid", mediaStatus="not_requested")
    play, flags = detail.get("vod_play_url"), detail.get("vod_play_from")
    if not isinstance(play, str) or not isinstance(flags, str):
        return
    first = play.split("$$$", 1)[0].split("#", 1)[0].partition("$")
    if not first[1] or not first[2] or not flags.split("$$$", 1)[0]:
        return
    try:
        player = worker["handle"]("play", {"flag": flags.split("$$$", 1)[0], "play": first[2]})
        if isinstance(player, str):
            player = json.loads(player)
        if not isinstance(player, dict):
            result["playerStatus"] = "invalid_output"
            return
        result["playerStatus"] = "returned"
        result["playerParse"] = ("direct" if type(player.get("parse", 0)) in {str, int}
                                 and str(player.get("parse", 0)) == "0"
                                 and type(player.get("jx", 0)) in {str, int}
                                 and str(player.get("jx", 0)) == "0"
                                 and player.get("playUrl", "") == "" else "parse_required")
        url = player.get("url")
        if not isinstance(url, str) or not url or url == "about:blank":
            result["playerStatus"] = "empty"
            return
        parsed = urlsplit(url)
        result["playerUrlKind"] = (parsed.scheme if parsed.scheme in {"https", "http"}
                                   and parsed.hostname and not parsed.username and not parsed.password
                                   and not parsed.fragment else "other")
        if result["playerParse"] != "direct" or result["playerUrlKind"] == "other":
            return
        headers = player.get("header", {})
        if isinstance(headers, str):
            headers = json.loads(headers)
        if not isinstance(headers, dict):
            result["mediaStatus"] = "invalid_headers"
            return
        try:
            response = requests.get(url, headers={**headers, "Range": "bytes=0-65535"}, timeout=5,
                                    allow_redirects=False)
            result["mediaStatus"] = ("prefix_returned" if response.status_code in {200, 206}
                                     and response.content else "http_failed")
            response.close()
        except egress_bridge.PolicyDenied:
            result["mediaStatus"] = "policy_denied"
        except Exception:
            result["mediaStatus"] = "http_failed"
    except egress_bridge.PolicyDenied:
        result["playerStatus"] = "policy_denied"
    except Exception:
        result["playerStatus"] = "exception"


def main():
    sys.path[:0] = [str(ROOT / "runtime"), str(ROOT / "managed"),
                    str(ROOT / "managed/vendor"), str(ROOT / "managed/vendor/pyquery.whl"),
                    str(ROOT / "managed/vendor/cssselect.whl")]
    import egress_bridge
    commands = egress_bridge.install(sys.stdin.buffer)
    sys.argv = [str(ROOT / "runtime/t4worker.py"), str(ROOT / "runtime/Atvp.py")]
    errors = ObservedErrors(sys.stderr)
    with contextlib.redirect_stderr(errors), contextlib.redirect_stdout(errors):
        namespace = runpy.run_path(sys.argv[0], run_name="managed_egress_source")
        worker = namespace["main"].__globals__
        install_loader_observer(worker, commands)
        install_home_merge(worker)
        install_session_proxy(worker)
        install_failure_observer(worker)
        emit({"kind": "loader_available"})
        # Run the pinned JSONL main, including its ID/error/stdout handling.
        # The bridge retains the binary stream for synchronous broker replies.
        previous = sys.stdin
        try:
            sys.stdin = WorkerInput(commands)
            worker["main"]()
        finally:
            sys.stdin = previous
        emit({"kind": "result", "summary": {"status": "source_exception"}})


class WorkerInput:
    def __init__(self, stream):
        self.stream, self.count = stream, 0

    def __iter__(self):
        return self

    def __next__(self):
        self.count += 1
        if self.count > 32:
            raise ValueError("LOADER_COMMAND_LIMIT")
        line = self.stream.readline(98305)
        if len(line) > 98304 or not line.endswith(b"\n"):
            raise ValueError("LOADER_COMMAND_INVALID")
        call = json.loads(line)
        if call == {"kind": "finish"}:
            raise StopIteration
        if (not isinstance(call, dict) or set(call) != {"kind", "id", "method", "params"}
                or call["kind"] != "call" or type(call["id"]) is not int
                or not isinstance(call["method"], str)):
            raise ValueError("LOADER_COMMAND_INVALID")
        return json.dumps({key: call[key] for key in ("id", "method", "params")})


def emit(frame):
    raw = json.dumps(frame, ensure_ascii=True, separators=(",", ":")).encode()
    if len(raw) > 98304:
        raise ValueError("LOADER_OUTPUT_LIMIT")
    os.write(1, raw + b"\n")


def rewrite_proxy_url(value):
    url = urlsplit(value)
    if (url.scheme != "http" or url.hostname not in {"127.0.0.1", "localhost"}
            or url.netloc not in {"127.0.0.1:9978", "localhost:9978"}
            or url.path != "/proxy" or url.fragment):
        return value
    try:
        query = parse_qsl(url.query, keep_blank_values=True, strict_parsing=True, max_num_fields=24)
    except ValueError:
        return value
    if dict(query).get("do") != "py" or len({key for key, _ in query}) != len(query):
        return value
    return "http://managed.invalid/local?" + urlencode(
        [(key, val) for key, val in query if key not in {"do", "site", "siteKey"}])


def install_failure_observer(worker):
    original = worker["_protocol_write"]

    def write(frame):
        error = sys.exc_info()[1]
        if frame.get("ok") is False and isinstance(error, Exception):
            context = {}
            trace = error.__traceback__
            while trace is not None:
                if trace.tb_frame.f_code.co_filename == "<atvp-inner>" and 1 <= trace.tb_lineno <= 1000000:
                    context["sourceLine"] = trace.tb_lineno
                if (trace.tb_frame.f_code.co_filename == str(ROOT / "runtime/Atvp.py")
                        and trace.tb_frame.f_code.co_name == "_build_backend_endpoint"
                        and isinstance(error, ValueError)
                        and not getattr(trace.tb_frame.f_locals.get("self"), "_backend_api", None)):
                    context["hostStage"] = "atvp_backend_endpoint"
                trace = trace.tb_next
            if isinstance(error, OSError) and type(error.errno) is int and 1 <= error.errno <= 4095:
                context["errno"] = error.errno
            if context:
                frame = {**frame, "error": {**frame["error"], "context": context}}
        original(frame)

    worker["_protocol_write"] = write


def install_loader_observer(worker, stream):
    load = worker["_load_atvp"]

    def observed_load():
        load()
        cls = type(worker["_spider"])
        inner = cls._load_inner_spider_class

        def observed_inner(instance, source_text):
            # This handshake occurs before compiling/executing any source-supplied code.
            emit({"kind": "loader_source",
                  "sourceTextSha256": hashlib.sha256(source_text.encode("utf-8")).hexdigest()})
            if stream.readline(128) != b'{"kind":"loader_ack"}\n':
                raise ValueError("LOADER_SOURCE_ACK_REQUIRED")
            return inner(instance, source_text)
        cls._load_inner_spider_class = observed_inner
    worker["_load_atvp"] = observed_load


def install_home_merge(worker):
    """Adapt the pinned worker without rewriting its historical runtime bundle."""
    original = worker["handle"]

    def handle(method, params):
        if method != "home" or worker["_spider"] is None:
            return original(method, params)
        with worker["_quiet_stdout"]():
            spider = worker["_spider"]

            def home_object(value, callback):
                if isinstance(value, str):
                    value = json.loads(value)
                    if spider._category_mode_enabled():
                        value = spider._normalize_category_content(value)
                if value is not None and not isinstance(value, dict):
                    raise ValueError(callback + " must return an object or null")
                if value is not None and value.get("list") is not None and not isinstance(value["list"], list):
                    raise ValueError(callback + " list must be an array or null")
                return value

            invoke = worker["_invoke"]
            home = home_object(invoke("homeContent", bool(params.get("filter", True))), "homeContent")
            result = dict(home) if home is not None else {}
            if getattr(spider._require_inner(), "homeVideoContent", None) is not None:
                video = home_object(invoke("homeVideoContent"), "homeVideoContent")
                if video is not None and video.get("list") is not None:
                    result["list"] = video["list"]
            if result.get("list") is None:
                result["list"] = []
            return result

    worker["handle"] = handle


def install_session_proxy(worker):
    original = worker["handle"]

    def handle(method, params):
        if method == "proxy":
            target = urlsplit(params["target"])
            if target.path != "/local" or target.scheme or target.netloc or target.fragment:
                raise ValueError("MANAGED_PROXY_TARGET_INVALID")
            pairs = parse_qsl(target.query, keep_blank_values=True, strict_parsing=True)
            if len(pairs) > 24 or len({key for key, _ in pairs}) != len(pairs):
                raise ValueError("MANAGED_PROXY_TARGET_INVALID")
            arguments = dict(pairs)
            arguments["headers"] = {"Range": params["range"]} if params["range"] else {}
            with worker["_quiet_stdout"]():
                value = original("local", arguments)
            if not isinstance(value, (list, tuple)) or not 3 <= len(value) <= 4:
                raise ValueError("MANAGED_PROXY_RESULT_INVALID")
            status, mime, body = value[:3]
            if isinstance(body, str):
                body = body.encode("utf-8")
            if (type(status) is not int or status not in {200, 206, 404, 416}
                    or not isinstance(mime, str) or len(mime) > 100 or "\r" in mime or "\n" in mime
                    or not isinstance(body, bytes) or len(body) > 2097152):
                raise ValueError("MANAGED_PROXY_RESULT_INVALID")
            headers = value[3] if len(value) == 4 else {}
            if not isinstance(headers, dict):
                raise ValueError("MANAGED_PROXY_RESULT_INVALID")
            safe = {key.lower(): val for key, val in headers.items()
                    if isinstance(key, str) and key.lower() in {"content-range", "accept-ranges"}
                    and isinstance(val, str) and len(val) <= 100 and "\r" not in val and "\n" not in val}
            media_type = mime.split(";", 1)[0].strip().lower()
            body_limit = 32768
            if media_type in {"application/vnd.apple.mpegurl", "application/x-mpegurl"}:
                body_limit = MAX_MANIFEST_BYTES
                if (status != 200 or params["range"] not in {"", "bytes=0-32767"}
                        or "content-range" in safe or len(body) > body_limit):
                    raise ValueError("MANAGED_PROXY_MANIFEST_INVALID")
                lines = []
                for line in body.decode("utf-8-sig").splitlines(keepends=True):
                    address = line.strip()
                    lines.append(line.replace(address, rewrite_proxy_url(address), 1)
                                 if address and not address.startswith("#") else line)
                body = "".join(lines).encode("utf-8")
            elif media_type in {"image/jpeg", "image/png", "image/webp", "image/gif"}:
                if status != 200 or params["range"] or "content-range" in safe:
                    raise ValueError("MANAGED_PROXY_IMAGE_INVALID")
                body_limit = MAX_IMAGE_BYTES
            elif status == 200 and media_type == "video/mp2t" and params["range"] == "bytes=0-32767" and body:
                # Range is applied to the source's decoded local representation, not the upstream response.
                total = len(body)
                body = body[:32768]
                status = 206
                safe.update({"content-range": "bytes 0-%d/%d" % (len(body) - 1, total),
                             "accept-ranges": "bytes"})
            if len(body) > body_limit:
                raise ValueError("MANAGED_PROXY_RESULT_INVALID")
            return {"status": status, "mime": mime, "headers": safe, "bytes": len(body),
                    "body": base64.b64encode(b"" if params["head"] else body).decode(),
                    "sha256": hashlib.sha256(b"" if params["head"] else body).hexdigest()}
        result = original(method, params)
        if method in {"home", "category", "detail", "search"}:
            if isinstance(result, str):
                try:
                    result = json.loads(result)
                except ValueError:
                    return result
            if isinstance(result, dict) and isinstance(result.get("list"), list):
                for row in result["list"]:
                    if isinstance(row, dict) and isinstance(row.get("vod_pic"), str):
                        row["vod_pic"] = rewrite_proxy_url(row["vod_pic"])
        if method == "play" and params.get("proxy_base") == "http://managed.invalid":
            if isinstance(result, str):
                result = json.loads(result)
            if isinstance(result, dict) and isinstance(result.get("url"), str):
                result["url"] = rewrite_proxy_url(result["url"])
        return result
    worker["handle"] = handle


if __name__ == "__main__":
    main()
