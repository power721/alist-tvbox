"""Read an observed public HTTPS Python input; never execute it or send AT credentials."""
from __future__ import annotations

import base64
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import re
import ssl
import sys
import time
from urllib.parse import urljoin

SPEC = importlib.util.spec_from_file_location("source_egress", Path(__file__).with_name("source_egress.py"))
egress = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(egress)

MAX_BYTES = 1_048_576
SOURCE_TYPES = {"text/plain", "text/x-python", "application/x-python-code", "application/octet-stream"}


def checked_url(url):
    egress.require(isinstance(url, str) and url.isascii() and len(url) <= 2048
                   and not re.search(r"[\x00-\x20\x7f\\]", url), "AT_PUBLIC_SOURCE_URL_INVALID")
    parsed, host, port = egress.http_authority(url, "AT_PUBLIC_SOURCE_URL_INVALID")
    egress.require(parsed.scheme == "https" and not parsed.fragment, "AT_PUBLIC_SOURCE_URL_INVALID")
    return parsed, host, port


def read_source(url, max_bytes=MAX_BYTES, *, resolver=None, broker_factory=None):
    egress.require(type(max_bytes) is int and 1 <= max_bytes <= MAX_BYTES, "AT_RESPONSE_TOO_LARGE")
    resolver = resolver or egress.public_dns_addresses
    broker_factory = broker_factory or egress.Broker
    deadline = time.monotonic() + 30
    visited = set()
    for redirect in range(4):
        parsed, host, port = checked_url(url)
        egress.require(url not in visited, "AT_PUBLIC_SOURCE_REDIRECT_LIMIT")
        visited.add(url)
        addresses = resolver(host, port=port)
        policy = {"schemaVersion": 2, "origin": parsed.scheme + "://" + parsed.netloc,
                  "paths": [], "exactUrls": [url], "methods": ["GET"], "queryKeys": [],
                  "maxRequests": 1, "maxResponseBytes": max_bytes,
                  "maxTotalResponseBytes": max_bytes, "minIntervalSeconds": 1, **egress.address_pins(addresses)}
        broker = broker_factory(policy)
        broker.bind_execution(deadline, lambda: False)
        response = broker.fetch({"url": url, "method": "GET", "headers": {
            "Accept": "text/plain, application/octet-stream, text/x-python"}, "body": ""})
        if response["status"] in {301, 302, 303, 307, 308}:
            location = response["headers"].get("Location")
            egress.require(redirect < 3 and location, "AT_PUBLIC_SOURCE_REDIRECT_LIMIT")
            url = urljoin(url, location)
            continue
        egress.require(response["status"] == 200, "AT_SOURCE_HTTP_ERROR")
        mime = next((value.split(";", 1)[0].strip().lower() for key, value in response["headers"].items()
                     if key.lower() == "content-type"), "")
        egress.require(mime in SOURCE_TYPES, "AT_SOURCE_RESPONSE_TYPE")
        body = base64.b64decode(response["body"], validate=True)
        egress.require(0 < len(body) <= max_bytes, "AT_SOURCE_EMPTY" if not body else "AT_RESPONSE_TOO_LARGE")
        return body
    raise egress.Denied("AT_PUBLIC_SOURCE_REDIRECT_LIMIT")


def main():
    try:
        value = json.loads(sys.stdin.buffer.read(8193))
        body = read_source(value["url"], value["maxBytes"])
        Path("source.py").write_bytes(body)
        result = {"status": "read", "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()}
    except egress.Denied as error:
        code = "AT_RESPONSE_TOO_LARGE" if error.code == "EGRESS_BYTE_BUDGET" else error.code
        result = {"status": "rejected", "code": code}
    except ssl.SSLError:
        result = {"status": "rejected", "code": "AT_PUBLIC_SOURCE_TLS_FAILED"}
    except TimeoutError:
        result = {"status": "rejected", "code": "AT_PUBLIC_SOURCE_TIMEOUT"}
    except OSError:
        result = {"status": "rejected", "code": "AT_PUBLIC_SOURCE_NETWORK_FAILED"}
    except (ValueError, KeyError, TypeError, http.client.HTTPException):
        result = {"status": "rejected", "code": "AT_PUBLIC_SOURCE_READ_FAILED"}
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
