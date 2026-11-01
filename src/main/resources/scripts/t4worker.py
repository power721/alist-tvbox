# -*- coding: utf-8 -*-
"""T4 服务端插件 worker:以子进程形态运行 Atvp.py 装载的爬虫插件。

Java 侧(PluginWorkerService)经 stdin/stdout 的 JSON 行协议调用:
  请求 {"id": str, "method": str, "params": {...}}
  响应 {"id": str, "ok": bool, "result": ..., "error": {"code": str, "message": str}}

约定(来自 sidecar worker 的踩坑记录, docs/t4-sidecar-borrow-assessment.md A1):
- 失败响应必须沿用请求 id,否则真实错误会被误判为协议错误;
- 插件调用期间 stdout 重定向到 stderr(插件乱 print 不污染协议通道);
- Atvp 与 inner 插件经 exec 装载进隔离模块,base.spider 做空 shim;
- 插件期望的 category 筛选参数形态(dict/JSON 字符串)以对密文源码的
  AST 静态探测为准(categoryContent 内是否 json.loads(extend))。

用法: python3 t4worker.py <atvp_path>
Python 3.10+(Alpine 3.21 自带 3.12)。
"""
import ast
import contextlib
import json
import os
import secrets
import socketserver
import sys
import threading
import types
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from t4cenc import (
    CENC_FRAGMENT_KEY,
    CencRangeReader,
    parse_cenc_audio_fragment,
    parse_cenc_media_fragment,
)

import requests

ATVP_PATH = sys.argv[1] if len(sys.argv) > 1 else "Atvp.py"

# base.spider 空 shim:inner 插件 import base.spider 时由 Atvp 的 _FallbackSpider 回填
_base = types.ModuleType("base")
_base_spider = types.ModuleType("base.spider")
_base.__path__ = []
sys.modules["base"] = _base
sys.modules["base.spider"] = _base_spider

_spider = None
_category_filter_json = False

# ---- worker 本地 HTTP 服务:CENC 解密代理(/cenc)+ 爬虫自起服务反代(/lp),Java 侧 /t4cenc 桥转发 ----
_CENC_CHUNK = 256 * 1024
_cenc_sessions = {}
_cenc_server = None
_cenc_lock = threading.Lock()

# ---- 爬虫自起的 loopback 服务(自定义 raw 插件流代理):端口记录 + /lp 反代 ----
# 自定义上传的 .py 插件(如 hgtv.py)会在 playerContent 里自起 127.0.0.1 流式代理,
# 返回的 URL 在 T4 下指向服务端 loopback,客户端不可达。装载爬虫前 patch
# TCPServer.server_bind 记录本进程绑定 loopback 的端口,play 时把指向这些端口的
# URL 改写为 /lp/{port}/ 桥地址,由 worker 本地服务反代回爬虫自己的服务。
_local_ports = set()
_local_ports_lock = threading.Lock()


def _install_local_port_probe():
    original = socketserver.TCPServer.server_bind

    def server_bind(self):
        original(self)
        try:
            host, port = self.server_address[:2]
            if str(host) in ("127.0.0.1", "localhost", "::1", "") and port:
                with _local_ports_lock:
                    _local_ports.add(int(port))
        except Exception:
            pass

    socketserver.TCPServer.server_bind = server_bind


_install_local_port_probe()


def _cenc_get(url, headers=None, timeout=20.0, follow_redirects=True):
    return requests.get(url, headers=headers, timeout=timeout, allow_redirects=follow_redirects)


def _ensure_cenc_server():
    global _cenc_server
    if _cenc_server is not None:
        return _cenc_server
    with _cenc_lock:
        if _cenc_server is None:
            _cenc_server = ThreadingHTTPServer(("127.0.0.1", 0), _CencHandler)
            threading.Thread(target=_cenc_server.serve_forever, daemon=True).start()
    return _cenc_server


class _CencHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_HEAD(self):
        self._serve(head_only=True)

    def do_GET(self):
        self._serve(head_only=False)

    def _serve(self, head_only):
        raw_path = urlparse(self.path).path
        if raw_path.startswith("/lp/"):
            self._proxy_local(head_only)
            return
        token = raw_path
        if not token.startswith("/cenc/"):
            self._fail(404, "not cenc path")
            return
        token = unquote(token[len("/cenc/"):].split("/", 1)[0])
        if "." in token:
            token = token.rsplit(".", 1)[0]
        reader = _cenc_sessions.get(token)
        if reader is None:
            self._fail(404, "missing cenc session")
            return
        try:
            total = reader.ensure_index().total_size
        except Exception as exc:
            self._fail(502, "cenc index: %s" % exc)
            return
        range_header = self.headers.get("Range") or ""
        start, end = _parse_range(range_header, total)
        if start is None:
            self._fail(416, "invalid range")
            return
        length = end - start + 1
        self.send_response(206 if range_header else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if range_header:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, total))
        self.end_headers()
        if head_only:
            return
        cursor = start
        try:
            while cursor <= end:
                chunk_end = min(cursor + _CENC_CHUNK - 1, end)
                chunk = reader.read_range(cursor, chunk_end)
                if not chunk:
                    break
                self.wfile.write(chunk)
                cursor += len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _fail(self, code, message):
        payload = message.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _proxy_local(self, head_only):
        # /lp/{port}/{rest} → http://127.0.0.1:{port}/{rest}(爬虫自起服务,Range/HEAD 透传流式拷贝)。
        # 端口限定 server_bind 记录集:未记录端口一律 404,不扩 SSRF 面。
        rest = self.path[len("/lp/"):]
        port_text = rest.split("/", 1)[0]
        if not port_text.isdigit():
            self._fail(404, "bad lp port")
            return
        port = int(port_text)
        with _local_ports_lock:
            if port not in _local_ports:
                self._fail(404, "unknown lp port")
                return
        tail = rest[len(port_text) + 1:] if len(rest) > len(port_text) else ""
        target = "http://127.0.0.1:%d/%s" % (port, tail)
        headers = {"Connection": "close"}
        range_header = self.headers.get("Range")
        if range_header:
            headers["Range"] = range_header
        request = urllib.request.Request(
            target, headers=headers, method="HEAD" if head_only else "GET")
        try:
            upstream = urllib.request.urlopen(request, timeout=60)
        except urllib.error.HTTPError as err:
            upstream = err  # 4xx/5xx(含爬虫侧 416)同样透传给播放器
        except Exception as exc:
            self._fail(502, "lp upstream: %s" % exc)
            return
        with upstream:
            self.send_response(upstream.status)
            for name, value in upstream.getheaders():
                if name.lower() in ("connection", "transfer-encoding", "content-encoding", "server", "date"):
                    continue
                self.send_header(name, value)
            self.send_header("Connection", "close")
            self.end_headers()
            if head_only:
                return
            try:
                while True:
                    chunk = upstream.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass


def _parse_range(range_header, total):
    if total <= 0:
        return None, None
    if not range_header:
        return 0, total - 1
    value = range_header.strip()
    if not value.lower().startswith("bytes="):
        return None, None
    body = value[6:]
    start_text, _, end_text = body.partition("-")
    try:
        start = int(start_text) if start_text else 0
        end = int(end_text) if end_text else total - 1
    except ValueError:
        return None, None
    if start < 0 or start >= total:
        return None, None
    # 播放器常发超出总长的末端(bytes=0-65535 对 511B 媒体),须钳制而非 416
    if end > total - 1:
        end = total - 1
    if end < start:
        return None, None
    return start, end


def _rewrite_cenc_play(result, proxy_base):
    """play 结果 url 带 #hg-cenc= 密钥片段 → 建解密会话,url 改写为代理地址。

    proxy_base 为空(单测/无请求上下文)保持原样;分离音频(红果电影 DASH 式)在
    payload.audio,亦建会话并附 `audio_url` 字段(T4 客户端支持外挂音轨时可用)。
    """
    if isinstance(result, str):
        try:
            result = json.loads(result)
        except ValueError:
            return result
    if not isinstance(result, dict):
        return result
    url = str(result.get("url") or "")
    if CENC_FRAGMENT_KEY not in url or not proxy_base:
        return result
    parsed = parse_cenc_media_fragment(url)
    if parsed is None:
        return result
    upstream, spade_a = parsed
    headers = result.get("header") if isinstance(result.get("header"), dict) else {}
    _ensure_cenc_server()
    token = secrets.token_urlsafe(16)
    # 注册即后台预热索引(与播放器启动重叠), 首请求命中热索引
    _cenc_sessions[token] = CencRangeReader(upstream, headers, spade_a, get=_cenc_get).warmup()
    result["url"] = proxy_base + "/cenc/" + token + ".mp4"
    audio = parse_cenc_audio_fragment(url)
    if audio is not None:
        audio_url, audio_spade = audio
        audio_token = secrets.token_urlsafe(16)
        _cenc_sessions[audio_token] = CencRangeReader(
            audio_url, headers, audio_spade or spade_a, get=_cenc_get).warmup()
        result["audio_url"] = proxy_base + "/cenc/" + audio_token + ".m4a"
    return result


def _rewrite_local_play(result, proxy_base):
    """play 结果 url 指向本 worker 内自起服务的 loopback 端口 → 改写为 /lp/ 桥地址。

    自定义 raw 插件(如上传到 plugins/ 的 hgtv.py)自起的流代理只在本进程 loopback
    可达,URL 原样下发 T4 客户端必然连接失败。端口必须是 server_bind 记录集内的
    (本进程自己绑定的服务);指向本机其它端口的原样透出(客户端仍不可达,但不借
    worker 桥面代理任意本机端口)。无 proxy_base(单测)保持原样。
    """
    if not proxy_base:
        return result
    if isinstance(result, str):
        try:
            result = json.loads(result)
        except ValueError:
            return result
    if not isinstance(result, dict):
        return result
    url = str(result.get("url") or "")
    if not url.startswith(("http://127.0.0.1:", "http://localhost:")):
        return result
    parsed = urlparse(url)
    try:
        port = int(parsed.port)
    except (TypeError, ValueError):
        return result
    with _local_ports_lock:
        known = port in _local_ports
    if not known:
        return result
    _ensure_cenc_server()
    tail = parsed.path.lstrip("/")
    if parsed.query:
        tail += "?" + parsed.query
    result["url"] = "%s/lp/%d/%s" % (proxy_base, port, tail)
    return result


@contextlib.contextmanager
def _quiet_stdout():
    buf = sys.stdout
    sys.stdout = sys.stderr
    try:
        yield
    finally:
        sys.stdout = buf


def _load_atvp():
    global _spider
    module = types.ModuleType("t4_atvp")
    module.__file__ = ATVP_PATH
    with open(ATVP_PATH, "rb") as handle:
        code = handle.read()
    exec(compile(code, ATVP_PATH, "exec"), module.__dict__)
    spider_cls = getattr(module, "Spider", None)
    if spider_cls is None:
        raise RuntimeError("Atvp.py does not export Spider")
    _install_filter_probe(spider_cls)
    _spider = spider_cls()
    fallback = getattr(module, "_FallbackSpider", None)
    if fallback is not None:
        _base_spider.Spider = fallback


def _install_filter_probe(spider_cls):
    # 钩住密文源码装载点,拿到源码后 AST 探测 categoryContent 的筛选参数形态
    global _category_filter_json
    original = getattr(spider_cls, "_load_inner_spider_class", None)
    if original is None:
        return

    def load_inner(instance, source_text):
        global _category_filter_json
        _category_filter_json = _category_expects_json(source_text)
        return original(instance, source_text)

    spider_cls._load_inner_spider_class = load_inner


def _category_expects_json(source_text):
    # categoryContent 内对最后一个参数做 json.loads(或 `x or '{}'` 形态) → 期望 JSON 字符串;
    # 其余(含直接 .get) → 期望 dict(设备端 Chaquopy 传 dict 的默认形态)
    try:
        tree = ast.parse(source_text)
    except (SyntaxError, ValueError):
        return False
    for node in ast.walk(tree):
        if not isinstance(node, ast.FunctionDef) or node.name != "categoryContent":
            continue
        args = node.args.args
        if not args:
            continue
        parameter = args[-1].arg
        for call in ast.walk(node):
            if not isinstance(call, ast.Call) or not call.args:
                continue
            func = call.func
            name = func.attr if isinstance(func, ast.Attribute) else (
                func.id if isinstance(func, ast.Name) else "")
            argument = call.args[0]
            direct = isinstance(argument, ast.Name) and argument.id == parameter
            defaulted = (isinstance(argument, ast.BoolOp) and isinstance(argument.op, ast.Or)
                         and isinstance(argument.values[0], ast.Name)
                         and argument.values[0].id == parameter)
            if name == "loads" and (direct or defaulted):
                return True
    return False


def _invoke(name, *args):
    method = getattr(_spider, name, None)
    if method is None:
        raise RuntimeError("Atvp method unavailable: " + name)
    result = method(*args)
    json.dumps(result, ensure_ascii=False, default=str)
    return result


def _params_extend(params):
    if _category_filter_json:
        value = params.get("extend")
        if value is None:
            return "{}"
        if isinstance(value, dict):
            return json.dumps(value, ensure_ascii=False)
        return str(value)
    value = params.get("extend")
    if value is None:
        return {}
    if isinstance(value, str):
        try:
            return json.loads(value) if value.strip() else {}
        except ValueError:
            return {}
    return value


def _normalize_result(method, result):
    # TVBox spider 契约:home/category/detail/search/play 返回 JSON 字符串;
    # 行协议层反序列化为对象,Java 侧直接透传(否则响应成带引号的字符串)
    if method in ("home", "category", "detail", "search", "play") and isinstance(result, str):
        try:
            return json.loads(result)
        except ValueError:
            return result
    return result


def handle(method, params):
    if method == "ping":
        return {"status": "ready"}
    if method == "port":
        # worker 本地服务端口(未启动=0);Java 侧 /t4cenc 桥转发目标(/cenc 解密 + /lp 爬虫自起服务反代)
        if _cenc_server is None:
            return 0
        return _cenc_server.server_address[1]
    if _spider is None:
        if method != "init":
            raise RuntimeError("worker is not initialized")
        _load_atvp()
        extend = str(params.get("extend") or "")
        with _quiet_stdout():
            result = _invoke("init", extend)
            name = _invoke("getName")
        return {"result": result, "name": name}

    if method == "home":
        with _quiet_stdout():
            return _invoke("homeContent", bool(params.get("filter", True)))
    if method == "category":
        with _quiet_stdout():
            return _invoke("categoryContent", str(params.get("t") or ""), str(params.get("pg") or "1"),
                           bool(params.get("filter", True)), _params_extend(params))
    if method == "detail":
        with _quiet_stdout():
            return _invoke("detailContent", [str(params.get("ids") or "")])
    if method == "search":
        with _quiet_stdout():
            return _invoke("searchContent", str(params.get("wd") or ""), bool(params.get("quick", False)),
                           str(params.get("pg") or "1"))
    if method == "play":
        with _quiet_stdout():
            return _rewrite_local_play(_rewrite_cenc_play(
                _invoke("playerContent", str(params.get("flag") or ""), str(params.get("play") or ""), []),
                str(params.get("proxy_base") or "")), str(params.get("proxy_base") or ""))
    if method == "action":
        with _quiet_stdout():
            return _invoke("action", str(params.get("do") or ""))
    if method == "local":
        with _quiet_stdout():
            return _invoke("localProxy", dict(params))
    if method == "destroy":
        with _quiet_stdout():
            destroy = getattr(_spider, "destroy", None)
            if destroy is not None:
                destroy()
        return {"status": "closed"}
    raise RuntimeError("unknown method: " + method)


def _protocol_write(payload):
    # 协议输出直写 fd1:与被改道到 stderr 的 print(含 Atvp 预热守护等后台线程的裸 print)
    # 物理隔离,任何线程的 stdout 输出都不可能混进协议流
    os.write(1, (json.dumps(payload, ensure_ascii=False, default=str) + "\n").encode("utf-8"))


def main():
    # 一切 print(任何线程)永久改道 stderr;协议通道只认 _protocol_write
    sys.stdout = sys.stderr
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request_id = ""
        try:
            request = json.loads(line)
            if not isinstance(request, dict):
                raise ValueError("request must be an object")
            request_id = str(request.get("id") or "")
            method = str(request.get("method") or "")
            params = request.get("params")
            if not method:
                raise ValueError("method is required")
            if params is None:
                params = {}
            if not isinstance(params, dict):
                raise ValueError("params must be an object")
            result = _normalize_result(method, handle(method, params))
            _protocol_write({"id": request_id, "ok": True, "result": result})
        except Exception as exc:  # noqa: BLE001 - 协议边界:一切异常转错误响应,沿用请求 id
            _protocol_write({"id": request_id, "ok": False,
                             "error": {"code": type(exc).__name__, "message": str(exc)}})


if __name__ == "__main__":
    main()
