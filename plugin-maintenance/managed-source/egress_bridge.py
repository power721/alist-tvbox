"""Requests adapter over untrusted stdio RPC; kernel networking stays disabled."""
from __future__ import annotations

import base64
from collections import deque
import http.client
import io
import json
import os
import ssl
import threading
from email.message import Message
from types import SimpleNamespace
from urllib.parse import urlsplit
import urllib.error
import urllib.request
import urllib.response

import requests

LOCK = threading.Lock()
COUNTER = 0
# HTTP replies carry up to 8 MiB as Base64 plus bounded headers; source output is separate.
MAX_LINE = 4 * ((8 * 1024 * 1024 + 2) // 3) + 16384


class BrokerInput:
    """Dispatch interleaved commands and HTTP replies through one physical reader."""
    def __init__(self, stream):
        self.stream = stream
        self.condition = threading.Condition()
        self.pending = {"command": deque(), "response": deque()}
        self.reading = False
        self.failed = False
        self.closed = False

    def readline(self, limit=MAX_LINE + 1, *, channel="response"):
        while True:
            with self.condition:
                if self.failed:
                    raise ValueError("EGRESS_INPUT_INVALID")
                if self.pending[channel]:
                    line = self.pending[channel].popleft()
                    if len(line) > limit:
                        self.failed = True
                        self.condition.notify_all()
                        raise ValueError("EGRESS_INPUT_LIMIT")
                    return line
                if self.closed:
                    return b""
                if self.reading:
                    self.condition.wait()
                    continue
                self.reading = True
            try:
                line = self.stream.readline(MAX_LINE + 1)
                if line:
                    if len(line) > MAX_LINE or not line.endswith(b"\n"):
                        raise ValueError("EGRESS_INPUT_LIMIT")
                    value = json.loads(line)
                    if not isinstance(value, dict):
                        raise ValueError("EGRESS_INPUT_INVALID")
                    if value.get("kind") in ("call", "finish", "loader_ack"):
                        target = "command"
                        if len(line) > 98304:
                            raise ValueError("EGRESS_INPUT_LIMIT")
                    elif "kind" not in value and type(value.get("id")) is int and type(value.get("ok")) is bool:
                        target = "response"
                    else:
                        raise ValueError("EGRESS_INPUT_INVALID")
            except BaseException:
                with self.condition:
                    self.reading = False
                    self.failed = True
                    self.condition.notify_all()
                raise
            with self.condition:
                self.reading = False
                if not line:
                    self.closed = True
                elif self.pending[target]:
                    self.failed = True
                else:
                    self.pending[target].append(line)
                self.condition.notify_all()


class CommandInput:
    def __init__(self, broker):
        self.broker = broker

    def readline(self, limit):
        return self.broker.readline(limit, channel="command")


class PolicyDenied(BaseException):
    """Stop this sample on a policy refusal instead of retrying an unauthorized dependency."""
    def __init__(self, message, *, retry_after=None):
        super().__init__(message)
        self.retry_after = retry_after


def send(adapter, request, **kwargs):
    global COUNTER
    if kwargs.get("proxies") or kwargs.get("cert"):
        raise requests.RequestException("EGRESS_REQUEST_OPTIONS_DENIED")
    # TLS is verified by the broker; source-side verify=False cannot disable it.
    body = request.body or b""
    if isinstance(body, str):
        body = body.encode("utf-8")
    if not isinstance(body, bytes) or len(body) > 65536:
        raise requests.RequestException("EGRESS_BODY_DENIED")
    headers = {key: value for key, value in request.headers.items()
               if key.lower() not in {"accept-encoding", "connection", "content-length"}}
    with LOCK:
        COUNTER += 1
        frame = {"kind": "fetch", "id": COUNTER, "request": {
            "method": request.method, "url": request.url, "headers": headers,
            "body": base64.b64encode(body).decode("ascii")}}
        raw = json.dumps(frame, ensure_ascii=True, separators=(",", ":")).encode() + b"\n"
        if len(raw) > 98304:
            raise requests.RequestException("EGRESS_REQUEST_LIMIT")
        os.write(1, raw)
        line = INPUT.readline(MAX_LINE + 1)
        if len(line) > MAX_LINE or not line.endswith(b"\n"):
            raise requests.RequestException("EGRESS_RESPONSE_LIMIT")
        value = json.loads(line)
        if value.get("id") != COUNTER:
            raise requests.RequestException("EGRESS_RESPONSE_INVALID")
        if value.get("ok") is not True:
            if value.get("code") in {"EGRESS_HTTP_FAILED", "EGRESS_HTTP_DEADLINE",
                                     "EGRESS_DNS_FAILED", "EGRESS_DNS_NO_ADDRESS", "EGRESS_NONPUBLIC_DNS"}:
                raise requests.RequestException(value["code"])
            raise PolicyDenied("EGRESS_DENIED", retry_after=value.get("retryAfter"))
        result = value["response"]
    response = requests.Response()
    response.status_code = result["status"]
    response.headers.update(result["headers"])
    response._content = base64.b64decode(result["body"], validate=True)
    response._content_consumed = True
    response.url = request.url
    response.request = request
    response.raw = io.BytesIO(response._content)
    cookie_headers = Message()
    for header in result.get("setCookies", []):
        cookie_headers.add_header("Set-Cookie", header)
    response.raw._original_response = SimpleNamespace(msg=cookie_headers)
    requests.cookies.extract_cookies_to_jar(response.cookies, request, response.raw)
    response.encoding = requests.utils.get_encoding_from_headers(response.headers)
    response.reason = ""
    return response


def _rpc_fetch(method, url, headers, body):
    """Send urllib traffic through the same bounded broker as Requests."""
    try:
        result = send(None, SimpleNamespace(method=method, url=url, headers=headers, body=body))
    except requests.RequestException as error:
        raise urllib.error.URLError(str(error)) from error
    message = Message()
    for key, value in result.headers.items():
        message.add_header(key, value)
    for cookie in result.raw._original_response.msg.get_all("Set-Cookie", []):
        message.add_header("Set-Cookie", cookie)
    raw_body = io.BytesIO(result.content)
    response = urllib.response.addinfourl(raw_body, message, url, result.status_code)
    response.msg = result.reason
    return response


def _urllib_open(handler, request):
    if request.has_proxy():
        raise urllib.error.URLError("EGRESS_PROXY_DENIED")
    body = request.data
    if body is not None and not isinstance(body, (bytes, str)):
        raise urllib.error.URLError("EGRESS_BODY_DENIED")
    context = getattr(handler, "_context", None)
    if context is not None and (getattr(context, "check_hostname", True) is False
                                or getattr(context, "verify_mode", None) != ssl.CERT_REQUIRED):
        raise urllib.error.URLError("EGRESS_TLS_OPTIONS_DENIED")
    headers = dict(request.header_items())
    # urllib adds Host before dispatch; the broker generates it from the approved URL.
    if (any(key.lower() == "host" for key in request.headers)
            or request.get_header("Host") != urlsplit(request.full_url).netloc):
        raise urllib.error.URLError("EGRESS_HEADERS_DENIED")
    headers = {key: value for key, value in headers.items() if key.lower() != "host"}
    return _rpc_fetch(request.get_method(), request.full_url, headers, body)


def _http_open(handler, request):
    return _urllib_open(handler, request)


def _https_open(handler, request):
    return _urllib_open(handler, request)


class BrokerHTTPResponse(io.BytesIO):
    def __init__(self, response):
        super().__init__(response.content)
        self.status, self.reason = response.status_code, response.reason
        self.version, self.length, self.chunked, self.will_close = 11, len(response.content), False, True
        self.headers = Message()
        for key, value in response.headers.items():
            if key.lower() != "set-cookie":
                self.headers.add_header(key, value)
        for cookie in response.raw._original_response.msg.get_all("Set-Cookie", []):
            self.headers.add_header("Set-Cookie", cookie)
        self.msg = self.headers

    def getheader(self, name, default=None):
        values = self.headers.get_all(name)
        return ", ".join(values) if values else default

    def getheaders(self):
        return list(self.headers.items())

    def info(self):
        return self.headers

    def isclosed(self):
        return self.closed


class BrokerHTTPConnection(http.client.HTTPConnection):
    _scheme = "http"

    def connect(self):
        # Sources never acquire a socket; TLS, DNS and cookies remain broker-owned.
        if self._tunnel_host or self.source_address:
            raise http.client.HTTPException("EGRESS_CONNECTION_OPTIONS_DENIED")

    def set_tunnel(self, *args, **kwargs):
        raise http.client.HTTPException("EGRESS_PROXY_DENIED")

    def request(self, method, url, body=None, headers=None, *, encode_chunked=False):
        if getattr(self, "_broker_response", None) is not None:
            raise http.client.CannotSendRequest("EGRESS_RESPONSE_PENDING")
        self.connect()
        if not isinstance(url, str) or not url.startswith("/") or url.startswith("//"):
            raise http.client.HTTPException("EGRESS_URL_DENIED")
        host = "[" + self.host + "]" if ":" in self.host else self.host
        authority = host + (":" + str(self.port) if self.port != self.default_port else "")
        headers = dict(headers or {})
        hosts = [value for key, value in headers.items() if key.lower() == "host"]
        if (len(hosts) > 1 or any(not isinstance(value, str) or value.lower() not in {
                authority.lower(), (host + ":" + str(self.port)).lower()} for value in hosts)):
            raise http.client.HTTPException("EGRESS_HEADERS_DENIED")
        headers = {key: value for key, value in headers.items() if key.lower() != "host"}
        if isinstance(body, str):
            body = body.encode("latin-1")
        try:
            response = send(None, SimpleNamespace(method=method, url=self._scheme + "://" + authority + url,
                                                  headers=headers, body=body))
        except requests.RequestException as error:
            raise http.client.HTTPException(str(error)) from error
        self._broker_response = BrokerHTTPResponse(response)

    def getresponse(self):
        response = getattr(self, "_broker_response", None)
        if response is None:
            raise http.client.ResponseNotReady("EGRESS_RESPONSE_UNAVAILABLE")
        self._broker_response = None
        return response

    def close(self):
        response = getattr(self, "_broker_response", None)
        if response is not None:
            response.close()
            self._broker_response = None
        super().close()


class BrokerHTTPSConnection(BrokerHTTPConnection):
    default_port = 443
    _scheme = "https"

    def __init__(self, *args, context=None, **kwargs):
        super().__init__(*args, **kwargs)


def install(stream):
    global INPUT
    INPUT = BrokerInput(stream)
    requests.adapters.HTTPAdapter.send = send
    urllib.request.HTTPHandler.http_open = _http_open
    urllib.request.HTTPSHandler.https_open = _https_open
    http.client.HTTPConnection = BrokerHTTPConnection
    http.client.HTTPSConnection = BrokerHTTPSConnection
    return CommandInput(INPUT)
