"""Exact-origin read-only HTTP mediator. Never resolves or connects a source-selected host."""
from __future__ import annotations

import ast
import base64
import copy
from email.utils import parsedate_to_datetime
import hashlib
from html.parser import HTMLParser
import http.client
import http.cookiejar
from http.cookies import CookieError, SimpleCookie
import ipaddress
import json
import math
import re
import socket
import ssl
import threading
import time
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit
from urllib.request import ProxyHandler, Request, build_opener
from email.message import Message
from types import SimpleNamespace

MAX_REQUESTS = 64
MAX_ROUTES = 64
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_RESPONSE_BYTES = 64 * 1024 * 1024


class Denied(Exception):
    def __init__(self, code, *, retry_after=None, dns_observation=None):
        super().__init__(code)
        self.code = code
        self.retry_after = retry_after
        self.dns_observation = dns_observation


def require(condition, code):
    if not condition:
        raise Denied(code)


def byte_range(value):
    match = re.fullmatch(r"bytes=([0-9]{1,19})-([0-9]{1,19})", value)
    require(match is not None, "EGRESS_RANGE_DENIED")
    start, end = map(int, match.groups())
    require(0 <= start <= end < 2 ** 63 - 1 and end - start < 65536, "EGRESS_RANGE_DENIED")
    return start, end


def byte_budget_error(policy, total, limit, observed, stage, reason):
    error = Denied("EGRESS_BYTE_BUDGET")
    error.request_diagnostic = {
        "kind": "byte_budget", "reason": reason, "stage": stage,
        "maxResponseBytes": policy["maxResponseBytes"],
        "maxTotalResponseBytes": policy["maxTotalResponseBytes"],
        "totalResponseBytesBefore": total, "effectiveResponseLimitBytes": limit,
        "observedResponseBytes": observed}
    return error


def require_header(condition, reason, name=None):
    if condition:
        return
    error = Denied("EGRESS_HEADERS_DENIED")
    error.request_diagnostic = {"kind": "header", "reason": reason}
    if isinstance(name, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9-]{0,63}", name):
        error.request_diagnostic["headerName"] = name.lower()
    raise error from None


def digest(body):
    return hashlib.sha256(body).hexdigest()


def retry_after(value, now):
    """Project timing, never retain the original response header."""
    if value is None:
        return {"kind": "absent", "seconds": None}
    if not isinstance(value, str) or len(value) > 128 or not value.isascii():
        return {"kind": "invalid", "seconds": None}
    value = value.strip(" \t")
    if re.fullmatch(r"[0-9]{1,128}", value):
        return {"kind": "delay", "seconds": int(value)}
    try:
        date = parsedate_to_datetime(value)
        if date.tzinfo is not None and date.utcoffset().total_seconds() == 0:
            return {"kind": "date", "seconds": max(0, math.ceil(date.timestamp() - now))}
    except (ValueError, TypeError, OverflowError, AttributeError):
        pass
    return {"kind": "invalid", "seconds": None}


LIMITS = {"methods", "maxRequests", "maxResponseBytes", "maxTotalResponseBytes", "minIntervalSeconds"}


def http_authority(value, code="EGRESS_POLICY_INVALID"):
    """Validate a literal HTTP(S) authority and return its host and effective port."""
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        raise Denied(code) from None
    host = parsed.hostname
    require(parsed.scheme in {"http", "https"} and host and "%" not in host
            and not parsed.username and not parsed.password, code)
    if ":" in host:
        try:
            ipaddress.IPv6Address(host)
        except ValueError:
            raise Denied(code) from None
        authority = "[" + host + "]"
    else:
        require(re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", host)
                and "." in host, code)
        authority = host
    if port is not None:
        authority += ":" + str(port)
    require(parsed.netloc == authority and (port is None or 1 <= port <= 65535), code)
    return parsed, host, port or (443 if parsed.scheme == "https" else 80)


def policy_from(value, *, allow_empty=False):
    if isinstance(value, dict) and value.get("schemaVersion") == 3:
        return routes_policy_from(value)
    required = {
        "schemaVersion", "origin", "paths", "methods", "maxRequests", "maxResponseBytes",
        "maxTotalResponseBytes", "minIntervalSeconds", "queryKeys", "pinnedIpv4"}
    require(isinstance(value, dict) and required <= set(value)
            and set(value) <= required | {"exactUrls", "headerOrigins", "exactReferers", "sessionToken",
                                         "requestScope", "pinnedIpv6"},
            "EGRESS_POLICY_INVALID")
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 2, "EGRESS_POLICY_INVALID")
    parsed, _, _ = http_authority(value["origin"])
    require(not parsed.path and not parsed.query and not parsed.fragment, "EGRESS_POLICY_INVALID")
    try:
        literal_address = ipaddress.ip_address(parsed.hostname)
    except ValueError:
        literal_address = None
    require(literal_address is None or public_address(str(literal_address)), "EGRESS_POLICY_INVALID")
    exact = value.get("exactUrls")
    if "requestScope" in value:
        require(value["requestScope"] == "origin" and exact is not None, "EGRESS_POLICY_INVALID")
    if exact is not None:
        require(type(exact) is list and (0 if allow_empty else 1) <= len(exact) <= 16
                and all(isinstance(url, str) and url.isascii() and len(url) <= 2048
                        and not re.search(r"[\x00-\x20\x7f\\]", url)
                        and http_authority(url)[0].scheme == parsed.scheme
                        and urlsplit(url).netloc == parsed.netloc
                        and not urlsplit(url).fragment for url in exact)
                and len(set(exact)) == len(exact), "EGRESS_POLICY_INVALID")
    if "sessionToken" in value:
        grant = value["sessionToken"]
        origin_grant = (grant == {"responseScope": "origin"} and value.get("requestScope") == "origin")
        require(origin_grant or isinstance(grant, dict) and set(grant) == {"requestUrl", "jsonPath"}
                and isinstance(grant["requestUrl"], str)
                and exact is not None and grant["requestUrl"] in exact
                and type(grant["jsonPath"]) is list and 1 <= len(grant["jsonPath"]) <= 4
                and all(isinstance(key, str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", key)
                        for key in grant["jsonPath"]), "EGRESS_POLICY_INVALID")
    require(type(value["paths"]) is list and (0 if exact is not None else 1) <= len(value["paths"]) <= 8
            and len(set(value["paths"])) == len(value["paths"])
            and all(isinstance(path, str) and re.fullmatch(r"/[A-Za-z0-9/_-]{0,100}", path)
                    and "//" not in path for path in value["paths"]), "EGRESS_POLICY_INVALID")
    require(type(value["methods"]) is list and 0 < len(value["methods"]) <= 3
            and len(set(value["methods"])) == len(value["methods"])
            and all(method in {"GET", "HEAD", "POST"} for method in value["methods"]), "EGRESS_POLICY_INVALID")
    require(type(value["queryKeys"]) is list and len(value["queryKeys"]) <= 8
            and all(isinstance(key, str) and re.fullmatch(r"[a-z_]{1,32}", key) for key in value["queryKeys"]),
            "EGRESS_POLICY_INVALID")
    origins = value.get("headerOrigins", [])
    require(type(origins) is list and len(origins) <= 4
            and all(isinstance(origin, str)
                    and not http_authority(origin)[0].path
                    and not http_authority(origin)[0].query
                    and not http_authority(origin)[0].fragment
                    and public_host(urlsplit(origin).hostname) for origin in origins),
            "EGRESS_POLICY_INVALID")
    referers = value.get("exactReferers", [])
    require(type(referers) is list and len(referers) <= 8
            and all(isinstance(referer, str) and referer.isascii() and len(referer) <= 2048
                    and not re.search(r"[\x00-\x20\x7f\\]", referer)
                    and http_authority(referer)[0].scheme + "://" + urlsplit(referer).netloc
                        in [value["origin"], *origins]
                    and not urlsplit(referer).fragment
                    for referer in referers)
            and len(set(referers)) == len(referers),
            "EGRESS_POLICY_INVALID")
    ipv6 = value.get("pinnedIpv6", [])
    require(type(value["pinnedIpv4"]) is list and type(ipv6) is list
            and 0 < len(value["pinnedIpv4"]) + len(ipv6) <= 16
            and all(isinstance(ip, str) and public_ipv4(ip) for ip in value["pinnedIpv4"]),
            "EGRESS_POLICY_INVALID")
    require(all(isinstance(ip, str) and public_address(ip) and ipaddress.ip_address(ip).version == 6
                for ip in ipv6), "EGRESS_POLICY_INVALID")
    if literal_address is not None:
        require(all(ipaddress.ip_address(ip) == literal_address for ip in [*value["pinnedIpv4"], *ipv6]),
                "EGRESS_POLICY_INVALID")
    for key, lower, upper in (("maxRequests", 1, MAX_REQUESTS), ("maxResponseBytes", 1, MAX_RESPONSE_BYTES),
                              ("maxTotalResponseBytes", 1, MAX_TOTAL_RESPONSE_BYTES), ("minIntervalSeconds", 1, 5)):
        require(type(value[key]) is int and lower <= value[key] <= upper, "EGRESS_POLICY_INVALID")
    return value


def response_session_tokens(body, grant):
    """Project token fields without retaining their JSON response as page evidence."""
    try:
        value = json.loads(body)
    except (ValueError, UnicodeError):
        return []
    if "jsonPath" in grant:
        for key in grant["jsonPath"]:
            value = value.get(key) if isinstance(value, dict) else None
        candidates = [value]
    else:
        candidates, pending, visited = [], [(value, 0)], 0
        while pending and visited < 128:
            current, depth = pending.pop(0)
            visited += 1
            if isinstance(current, dict):
                for key, item in current.items():
                    if key.replace("_", "").lower() in {"token", "accesstoken", "authtoken"}:
                        candidates.append(item)
                    if depth < 3 and isinstance(item, (dict, list)):
                        pending.append((item, depth + 1))
                    if len(candidates) >= 4 or len(pending) >= 128:
                        break
            elif isinstance(current, list) and depth < 3:
                pending.extend((item, depth + 1) for item in current[:128 - len(pending)]
                               if isinstance(item, (dict, list)))
            if len(candidates) >= 4:
                break
    return list(dict.fromkeys("Bearer " + token for token in candidates
                             if isinstance(token, str) and 0 < len(token) <= 2041 and token.isascii()
                             and not re.search(r"[\x00-\x20\x7f]", token)))[:4]


def routes_policy_from(value):
    required = {"schemaVersion", "routes"} | LIMITS
    require(required <= set(value) <= required | {
                "responseUrls", "hlsOrigins", "responseQueries", "sessionTokenGroups", "resourceEvidence",
                "responseRedirects", "responseApiOrigins", "pendingOrigins", "playerResources"}
            and type(value["schemaVersion"]) is int and value["schemaVersion"] == 3,
            "EGRESS_POLICY_INVALID")
    routes = value["routes"]
    require(type(routes) is list and 1 <= len(routes) <= MAX_ROUTES, "EGRESS_POLICY_INVALID")
    origins, urls = set(), set()
    for route in routes:
        require(isinstance(route, dict)
                and {"origin", "exactUrls", "pinnedIpv4"} <= set(route)
                and set(route) <= {"origin", "exactUrls", "pinnedIpv4", "headerOrigins",
                                   "exactReferers", "sessionToken", "requestScope", "pinnedIpv6"},
                "EGRESS_POLICY_INVALID")
        policy_from(route_policy(value, route), allow_empty=True)
        require(route["origin"] not in origins, "EGRESS_POLICY_INVALID")
        origins.add(route["origin"])
        urls.update(route["exactUrls"])
    pending = value.get("pendingOrigins", [])
    require(type(pending) is list and len(pending) + len(routes) <= MAX_ROUTES
            and all(isinstance(origin, str) for origin in pending)
            and len(set(pending)) == len(pending) and not origins.intersection(pending),
            "EGRESS_POLICY_INVALID")
    for origin in pending:
        parsed, host, _ = http_authority(origin)
        require(not parsed.path and not parsed.query and not parsed.fragment
                and public_host(host)
                and all(route.get("requestScope") == "origin" for route in routes),
                "EGRESS_POLICY_INVALID")
    if "responseRedirects" in value:
        require(value["responseRedirects"] in {"public_https", "public_http_https"}
                and all(route.get("requestScope") == "origin" for route in routes), "EGRESS_POLICY_INVALID")
    if "responseApiOrigins" in value:
        require(value["responseApiOrigins"] in {"public_https", "public_http_https"}
                and all(route.get("requestScope") == "origin" for route in routes), "EGRESS_POLICY_INVALID")
    if "playerResources" in value:
        require(value["playerResources"] in {"public_https", "public_http_https"}, "EGRESS_POLICY_INVALID")
    groups = value.get("sessionTokenGroups", [])
    require(type(groups) is list and len(groups) <= 4, "EGRESS_POLICY_INVALID")
    grouped = set()
    issuers = {route["origin"] for route in routes if "sessionToken" in route} | set(pending)
    for group in groups:
        require(type(group) is list and 2 <= len(group) <= 8
                and all(isinstance(origin, str) and origin in origins | set(pending) for origin in group)
                and len(set(group)) == len(group) and not grouped.intersection(group)
                and bool(issuers.intersection(group)), "EGRESS_POLICY_INVALID")
        grouped.update(group)
    hls = value.get("hlsOrigins", [])
    require(type(hls) is list and len(hls) <= 4
            and all(isinstance(origin, str) and origin in origins for origin in hls)
            and len(set(hls)) == len(hls), "EGRESS_POLICY_INVALID")
    rules = value.get("responseUrls", [])
    require(type(rules) is list and len(rules) <= 4, "EGRESS_POLICY_INVALID")
    for rule in rules:
        require(isinstance(rule, dict) and set(rule) == {"requestUrl", "jsonPath", "targetOrigin"}
                and isinstance(rule["requestUrl"], str) and rule["requestUrl"] in urls
                and isinstance(rule["targetOrigin"], str) and rule["targetOrigin"] in origins
                and type(rule["jsonPath"]) is list and 1 <= len(rule["jsonPath"]) <= 4
                and all(isinstance(key, str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", key)
                        for key in rule["jsonPath"]), "EGRESS_POLICY_INVALID")
    response_query_rules_from(value.get("responseQueries", []), urls, origins)
    if "resourceEvidence" in value:
        evidence = value["resourceEvidence"]
        require(isinstance(evidence, dict) and set(evidence) == {"entryUrls"}
                and type(evidence["entryUrls"]) is list and 1 <= len(evidence["entryUrls"]) <= 2
                and all(isinstance(url, str) and url in urls and not urlsplit(url).query
                        for url in evidence["entryUrls"])
                and len(set(evidence["entryUrls"])) == len(evidence["entryUrls"]), "EGRESS_POLICY_INVALID")
    return value


def response_query_rules_from(rules, urls, origins=None):
    require(type(rules) is list and len(rules) <= 2, "EGRESS_POLICY_INVALID")
    query_targets = {rule.get("targetUrl") for rule in rules
                     if isinstance(rule, dict) and rule.get("property") == "apiToken"
                     and isinstance(rule.get("targetUrl"), str)}
    for rule in rules:
        if isinstance(rule, dict) and rule.get("property") == "apiToken":
            require(set(rule) == {"requestUrl", "targetUrl", "property"}
                    and isinstance(rule["requestUrl"], str) and rule["requestUrl"] in urls
                    and isinstance(rule["targetUrl"], str), "EGRESS_POLICY_INVALID")
            target = urlsplit(rule["targetUrl"])
            request = urlsplit(rule["requestUrl"])
            require(target.scheme in {"http", "https"} and target.scheme == request.scheme
                    and target.netloc == request.netloc
                    and target.path == "/api/resolve.php" and not target.query and not target.fragment
                    and rule["targetUrl"] == target.scheme + "://" + target.netloc + target.path,
                    "EGRESS_POLICY_INVALID")
        elif isinstance(rule, dict) and rule.get("property") == "jsonUrl":
            require(set(rule) == {"requestUrl", "targetOrigin", "jsonPath", "property"}
                    and isinstance(rule["requestUrl"], str)
                    and rule["requestUrl"] in query_targets
                    and isinstance(rule["targetOrigin"], str)
                    and origins is not None and rule["targetOrigin"] in origins
                    and type(rule["jsonPath"]) is list and 1 <= len(rule["jsonPath"]) <= 4
                    and all(isinstance(key, str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", key)
                            for key in rule["jsonPath"]), "EGRESS_POLICY_INVALID")
        else:
            raise Denied("EGRESS_POLICY_INVALID")
    return rules


def response_query_urls(url, body, rules):
    """Derive a single exact capability from an explicitly approved inline public nonce."""
    selected = [rule for rule in rules
                if rule["property"] == "apiToken" and rule["requestUrl"] == url]
    if not selected or len(body) > 2097152:
        return []

    class Scripts(HTMLParser):
        def __init__(self):
            super().__init__(convert_charrefs=False)
            self.active = False
            self.parts = []

        def handle_starttag(self, tag, attrs):
            if tag == "script":
                self.active = "src" not in dict(attrs)

        def handle_endtag(self, tag):
            if tag == "script":
                self.active = False

        def handle_data(self, data):
            if self.active:
                self.parts.append(data)

    try:
        parser = Scripts()
        parser.feed(body.decode("utf-8-sig", errors="strict"))
    except (ValueError, UnicodeError):
        return []
    text = "\n".join(parser.parts)
    matches = re.findall(r"""(?<![A-Za-z0-9_$])apiToken\s*:\s*(["'])([A-Za-z0-9._~+/=-]{1,512})\1""",
                         text)
    if len(matches) != 1:
        return []
    return [rule["targetUrl"] + "?" + urlencode({"token": matches[0][1]}) for rule in selected]


def response_json_urls(url, body, rules):
    endpoint = urlsplit(url)._replace(query="").geturl()
    selected = [rule for rule in rules
                if rule["property"] == "jsonUrl" and endpoint == rule["requestUrl"]]
    if not selected or len(body) > 2097152:
        return []
    try:
        document = json.loads(body.decode("utf-8-sig"))
    except (ValueError, UnicodeError):
        return []
    result = []
    for rule in selected:
        target = document
        for key in rule["jsonPath"]:
            target = target.get(key) if isinstance(target, dict) else None
        if isinstance(target, str):
            result.append((target, rule["targetOrigin"]))
    return result


def route_policy(policy, route):
    return {"schemaVersion": 2, "paths": [], "queryKeys": [],
            **{key: policy[key] for key in LIMITS}, **route}


def public_ipv4(address):
    try:
        ip = ipaddress.ip_address(address)
        return (ip.version == 4 and ip.is_global and not ip.is_multicast and not ip.is_reserved
                and not ip.is_loopback and not ip.is_link_local and not ip.is_unspecified)
    except ValueError:
        return False


def public_address(address):
    try:
        ip = ipaddress.ip_address(address)
        if (not ip.is_global or ip.is_multicast or ip.is_reserved or ip.is_loopback
                or ip.is_link_local or ip.is_unspecified or getattr(ip, "is_site_local", False)
                or getattr(ip, "scope_id", None)):
            return False
        if ip.version == 6:
            if ip.ipv4_mapped is not None and not public_ipv4(str(ip.ipv4_mapped)):
                return False
            if ip.sixtofour is not None and not public_ipv4(str(ip.sixtofour)):
                return False
            if ip.teredo is not None and not all(public_ipv4(str(item)) for item in ip.teredo):
                return False
        return True
    except ValueError:
        return False


def public_host(host):
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return ":" not in host and "%" not in host and not re.fullmatch(r"[0-9.]+", host)
    return public_address(host)


def address_pins(addresses):
    require(type(addresses) is list and 0 < len(addresses) <= 16
            and all(isinstance(address, str) and public_address(address) for address in addresses),
            "EGRESS_NONPUBLIC_DNS")
    pins = {"pinnedIpv4": [str(ipaddress.ip_address(address)) for address in addresses
                           if ipaddress.ip_address(address).version == 4]}
    ipv6 = [str(ipaddress.ip_address(address)) for address in addresses
            if ipaddress.ip_address(address).version == 6]
    if ipv6:
        pins["pinnedIpv6"] = ipv6
    return pins


def public_dns_addresses(host, *, port=443, observations=None):
    """Resolve approved names publicly; never connect to a DNS placeholder address."""
    try:
        literal = ipaddress.ip_address(host)
    except ValueError:
        literal = None
    if literal is not None:
        require(public_address(host), "EGRESS_NONPUBLIC_TARGET_DENIED")
        return [str(literal)]

    def resolve(secondary=False, record_type=1):
        started = time.monotonic()
        observed = {"dnsStatus": None, "answerCount": 0, "publicIpv4Count": 0,
                    "resolver": "cloudflare" if secondary else "alidns",
                    "recordType": "AAAA" if record_type == 28 else "A"}
        if record_type == 28:
            observed.update(recordType="AAAA", publicIpv6Count=0)
        if secondary:
            observed["fallbackFrom"] = "alidns"

        def record(kind):
            observed.update(kind=kind, elapsedMilliseconds=round((time.monotonic() - started) * 1000))
            if observations is not None:
                observations.append(copy.deepcopy(observed))

        def fail(code, kind):
            record(kind)
            raise Denied(code, dns_observation=copy.deepcopy(observed))

        try:
            query = urlencode({"name": host, "type": "AAAA" if record_type == 28 else "A"})
            if secondary:
                connection = http.client.HTTPSConnection("1.1.1.1", timeout=5,
                                                        context=ssl.create_default_context())
                try:
                    connection.request("GET", "/dns-query?" + query,
                                       headers={"Accept": "application/dns-json"})
                    response = connection.getresponse()
                    if response.status != 200:
                        fail("EGRESS_DNS_FAILED", "resolver_error")
                    body = response.read(16385)
                finally:
                    connection.close()
            else:
                opener = build_opener(ProxyHandler({}))
                with opener.open("https://dns.alidns.com/resolve?" + query, timeout=5) as response:
                    body = response.read(16385)
            if len(body) > 16384:
                fail("EGRESS_DNS_FAILED", "invalid_response")
            value = json.loads(body)
            status = value.get("Status")
            if type(status) is not int or not 0 <= status <= 65535:
                fail("EGRESS_DNS_FAILED", "invalid_response")
            observed["dnsStatus"] = status
            if status not in {0, 3}:
                fail("EGRESS_DNS_FAILED", "resolver_error")
            answers = value.get("Answer", [])
            if not isinstance(answers, list) or any(not isinstance(row, dict) for row in answers):
                fail("EGRESS_DNS_FAILED", "invalid_response")
            rows = [row.get("data") for row in answers if row.get("type") == record_type]
            observed["answerCount"] = len(rows)
            if status == 0 and not rows and record_type == 1:
                record("no_address")
                return resolve(secondary, 28)
            if status == 3 or not rows:
                fail("EGRESS_DNS_NO_ADDRESS", "no_address")
            if len(rows) > 16 or any(not isinstance(address, str) for address in rows):
                fail("EGRESS_DNS_FAILED", "invalid_response")
            addresses = list(dict.fromkeys(rows))
            observed["publicIpv4Count"] = sum(public_ipv4(address) for address in addresses)
            if record_type == 28:
                observed["publicIpv6Count"] = sum(":" in address and public_address(address)
                                                  for address in addresses)
            for address in addresses:
                try:
                    if ipaddress.ip_address(address).version != (6 if record_type == 28 else 4):
                        fail("EGRESS_DNS_FAILED", "invalid_response")
                except ValueError:
                    fail("EGRESS_DNS_FAILED", "invalid_response")
            if not all(public_address(address) for address in addresses):
                # A second public answer can replace only all-placeholder replies,
                # never a private, loopback or mixed public/private destination.
                if not secondary and record_type == 1 and all(address == "0.0.0.0" or ipaddress.ip_address(address)
                        in ipaddress.ip_network("198.18.0.0/15") for address in addresses):
                    record("nonpublic_address")
                    return resolve(True)
                fail("EGRESS_NONPUBLIC_DNS", "nonpublic_address")
            record("success")
            return list(dict.fromkeys(str(ipaddress.ip_address(address)) for address in addresses))
        except (OSError, http.client.HTTPException, ValueError, KeyError, TypeError, AttributeError):
            fail("EGRESS_DNS_FAILED", "invalid_response" if "body" in locals() else "transport_error")

    return resolve()


def response_content_type(response):
    mime = response.getheader("Content-Type", "").split(";", 1)[0].strip().lower()
    return mime if re.fullmatch(r"[a-z0-9!#$&^_.+-]{1,64}/[a-z0-9!#$&^_.+-]{1,64}", mime) else ""


def approved_addresses(host, resolver=socket.getaddrinfo, *, port=443):
    result, errors = [], []
    complete = threading.Event()

    def resolve():
        try:
            result.extend(resolver(host, port, family=socket.AF_UNSPEC, type=socket.SOCK_STREAM))
        except OSError:
            errors.append(True)
        finally:
            complete.set()

    threading.Thread(target=resolve, daemon=True).start()
    require(complete.wait(timeout=3) and not errors, "EGRESS_DNS_FAILED")
    rows = result
    addresses = sorted({row[4][0] for row in rows})
    require(addresses and len(addresses) <= 16 and all(public_address(ip) for ip in addresses),
            "EGRESS_NONPUBLIC_DNS")
    return addresses


def validate_request(value, policy, *, source_headers=(), session_tokens=(), referer_origins=()):
    require(isinstance(value, dict) and set(value) == {"method", "url", "headers", "body"},
            "EGRESS_REQUEST_INVALID")
    method, url, headers = value["method"], value["url"], value["headers"]
    require(isinstance(method, str) and method in policy["methods"], "EGRESS_METHOD_DENIED")
    require(isinstance(url, str) and url.isascii() and len(url) <= 2048
            and not re.search(r"[\x00-\x20\x7f\\]", url), "EGRESS_URL_DENIED")
    parsed = urlsplit(url)
    try:
        literal_address = ipaddress.ip_address(parsed.hostname or "")
    except ValueError:
        literal_address = None
    require(literal_address is None or public_address(str(literal_address)), "EGRESS_NONPUBLIC_TARGET_DENIED")
    exact = policy.get("exactUrls")
    query = []
    if exact is None:
        try:
            query = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=8)
        except ValueError:
            raise Denied("EGRESS_URL_DENIED") from None
    policy_origin, _, _ = http_authority(policy["origin"], "EGRESS_POLICY_INVALID")
    require(parsed.scheme == policy_origin.scheme
            and parsed.netloc == policy_origin.netloc
            and not parsed.username and not parsed.password and not parsed.fragment
            and (policy.get("requestScope") == "origin" or (url in exact if exact is not None else
                 parsed.path in policy["paths"]
                 and len({key for key, _ in query}) == len(query)
                 and all(key in policy["queryKeys"] and re.fullmatch(r"[0-9]{1,32}", val)
                         for key, val in query))), "EGRESS_URL_DENIED")
    require_header(isinstance(headers, dict), "header_shape")
    require_header(len(headers) <= 24, "header_count")
    allowed = {"accept", "content-type", "user-agent", "x-requested-with", "x-request-id", "x-timestamp", "x-sign",
               "x-signature", "x-device-id", "x-session-id", "x-client", "x-platform",
               "x-app-version", "x-content-encrypted", "x-iv", "x-request-iv", "x-api-version",
               "x-enc", "x-rid", "x-ts", "x-nonce", "x-device", "x-session", "origin", "referer",
               "devicebrand", "deviceid", "devicemodel", "devicetype", "requestid", "sessionid",
               "sign", "systemname", "systemversion", "time", "token", "version", "accept-language",
               "cache-control", "pragma", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
               "sec-fetch-user", "upgrade-insecure-requests", "range", "dnt", "priority",
               "connection", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
               "sec-fetch-storage-access", "platform", "client", "x-salt"}
    observed, total = set(), 0
    for key, val in headers.items():
        require_header(isinstance(key, str), "name_type")
        require_header(key.lower() in allowed or key.lower() in source_headers, "header_not_allowed", key)
        require_header(key.lower() not in observed, "duplicate_name", key)
        require_header(isinstance(val, str), "value_type", key)
        require_header(len(val) <= 2048, "value_size", key)
        require_header(val.isascii(), "value_encoding", key)
        require_header(not re.search(r"[\x00-\x1f\x7f]", val), "value_control", key)
        observed.add(key.lower())
        total += len(key) + len(val)
        if key.lower() == "token":
            require(val == "" or "Bearer " + val in session_tokens, "EGRESS_ACCOUNT_TOKEN_DENIED")
        if key.lower() == "range":
            require(method == "GET", "EGRESS_RANGE_DENIED")
            byte_range(val)
        if key.lower() in {"origin", "referer"}:
            origins = [policy["origin"], *policy.get("headerOrigins", [])]
            if key.lower() == "referer" and policy.get("requestScope") == "origin":
                origins.extend(referer_origins)
            allowed_origins = {entry + suffix for entry in origins for suffix in ("", "/", "/api")}
            if key.lower() == "referer":
                allowed_origins.update(policy.get("exactReferers", []))
            origin_referer = False
            if key.lower() == "referer" and policy.get("requestScope") == "origin":
                referer = urlsplit(val)
                origin_referer = (referer.scheme + "://" + referer.netloc in origins
                                  and not referer.username and not referer.password
                                  and not referer.fragment and "\\" not in val)
            if val not in allowed_origins and not origin_referer:
                error = Denied("EGRESS_HEADER_ORIGIN_DENIED")
                error.request_diagnostic = {
                    "kind": "header", "reason": "origin_not_allowed", "headerName": key.lower(),
                    "requestUrlSha256": digest(url.encode("ascii")),
                    "requestOriginSha256": digest(policy["origin"].encode("ascii"))}
                try:
                    authority, _, _ = http_authority(val, "EGRESS_HEADER_ORIGIN_DENIED")
                    error.request_diagnostic["headerOriginSha256"] = digest(
                        (authority.scheme + "://" + authority.netloc).encode("ascii"))
                except (Denied, ValueError):
                    pass
                raise error
    require_header(total <= 8192, "total_bytes")
    require(isinstance(value["body"], str) and len(value["body"]) <= 87384, "EGRESS_BODY_DENIED")
    try:
        body = base64.b64decode(value["body"], validate=True)
    except ValueError:
        raise Denied("EGRESS_BODY_DENIED") from None
    require(len(body) <= 65536 and (method == "POST" or not body), "EGRESS_BODY_DENIED")
    return parsed, body


def registered_query_separator_mismatch(url, method, policy):
    if method != "GET" or not isinstance(url, str) or not url.isascii() or len(url) > 2048:
        return None
    for registered in policy.get("exactUrls", []):
        if isinstance(registered, str) and "?" in registered and url == registered.replace("?", "", 1):
            return {
                "method": method,
                "requestUrlSha256": digest(url.encode("ascii")),
                "registeredUrlSha256": digest(registered.encode("ascii")),
                "registeredQuerySeparatorMissing": True,
            }
    return None


def url_denial_diagnostic(url, method, policy=None):
    """Explain a refused address without retaining its host, path, or query."""
    reason = "origin_not_registered"
    origin_sha = None
    scheme = None
    if not isinstance(url, str):
        reason = "url_type"
    elif len(url) > 2048:
        reason = "url_length"
    elif not url.isascii():
        reason = "url_encoding"
    elif re.search(r"[\x00-\x20\x7f\\]", url):
        reason = "url_control"
    else:
        try:
            parsed = urlsplit(url)
            if parsed.username or parsed.password:
                reason = "url_userinfo"
            elif parsed.fragment:
                reason = "url_fragment"
            elif parsed.scheme not in {"http", "https"}:
                reason = "url_scheme"
            else:
                scheme = parsed.scheme
                authority, _, _ = http_authority(url, "EGRESS_URL_DENIED")
                origin_sha = digest((authority.scheme + "://" + authority.netloc).encode("ascii"))
                if policy is not None:
                    reason = "url_not_registered"
                    if policy.get("requestScope") == "origin":
                        reason = "origin_mismatch"
        except (ValueError, Denied):
            reason = "url_syntax"
    result = {"kind": "url", "reason": reason}
    if isinstance(method, str) and method in {"GET", "HEAD", "POST"}:
        result["method"] = method
    if isinstance(url, str):
        result["requestUrlSha256"] = digest(url.encode("utf-8", errors="replace"))
    if scheme is not None:
        result["requestScheme"] = scheme
    if origin_sha is not None:
        result["requestOriginSha256"] = origin_sha
    return result


class ApiConfigScripts(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.active = False
        self.parts = []
        self.documents = []
        self.inline_scripts = []
        self.asset_urls = []
        self.json_script = False

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        resource_attributes = {
            "iframe": ("src",), "script": ("src",),
            "video": ("src", "data-source", "poster", "data-poster"),
            "source": ("src", "data-src"), "img": ("src", "data-src"),
        }
        for key in (*resource_attributes.get(tag, ()), "data-prefix"):
            if attributes.get(key):
                self.asset_urls.append(attributes[key])
        if tag == "meta" and attributes.get("property", "").lower() in {
                "og:video", "og:video:secure_url", "og:image"} and attributes.get("content"):
            self.asset_urls.append(attributes["content"])
        if tag == "script":
            self.json_script = (attributes.get("id") == "__NUXT_DATA__"
                                or attributes.get("type", "").lower() == "application/json")
            self.active = not attributes.get("src") and (
                self.json_script or attributes.get("type", "").lower() in {
                    "", "module", "text/javascript", "application/javascript"})
            self.parts = []

    def handle_data(self, data):
        if self.active:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if tag == "script" and self.active:
            target = self.documents if self.json_script else self.inline_scripts
            target.append("".join(self.parts))
            self.active = False
            self.parts = []


def javascript_url_literals(text):
    """Read static endpoint data, not expressions or executable JavaScript."""
    quoted = re.compile(r'''"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|`(?:\\.|[^`\\])*`''', re.DOTALL)
    regex = re.compile(r"/(?:\\[^\r\n]|\[(?:\\[^\r\n]|[^\]\\\r\n])*\]|[^/\\[\r\n])+/[a-z]*")
    word = re.compile(r"[a-zA-Z_$][a-zA-Z0-9_$]*")
    newline = re.compile(r"[\r\n]")
    expression_words = {"return", "throw", "case", "delete", "void", "typeof", "new",
                        "in", "instanceof", "yield", "await", "else", "do"}
    control_words = {"if", "while", "for", "with", "switch", "catch"}
    index = 0
    expression, previous, parentheses = True, "", []
    while index < len(text):
        char = text[index]
        if char.isspace():
            index += 1
            continue
        if text.startswith("//", index):
            end = newline.search(text, index + 2)
            if end is None:
                return
            index = end.end()
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            if end == -1:
                return
            index = end + 2
            continue
        if char == "/" and expression:
            match = regex.match(text, index)
            if match is None:
                return
            index = match.end()
            expression, previous = False, ""
            continue
        if char not in "\"'`":
            match = word.match(text, index)
            if match is not None:
                previous = match.group()
                expression = previous in expression_words
                index = match.end()
                continue
            if char == "(":
                parentheses.append(previous in control_words)
                expression = True
            elif char == ")":
                expression = parentheses.pop() if parentheses else False
            elif text[index:index + 2] in {"++", "--"}:
                index += 2
                previous = ""
                continue
            else:
                expression = char in "=([{,:;!?&|+-*%^~<>/"
            previous = ""
            index += 1
            continue
        match = quoted.match(text, index)
        if match is None:
            return
        literal = match.group()
        index = match.end()
        expression, previous = False, ""
        raw = literal[1:-1]
        if "${" in raw:
            continue
        try:
            # Preserve escape tokens while converting single/backtick delimiters to JSON.
            parts = re.split(r"(\\.)", raw, flags=re.DOTALL)
            encoded = "".join(json.dumps(part)[1:-1] if offset % 2 == 0 else
                              json.dumps(part[1:])[1:-1] if part in {"\\'", "\\`"} else part
                              for offset, part in enumerate(parts))
            target = json.loads('"' + encoded + '"')
        except (ValueError, RecursionError):
            continue
        if target.startswith(("http://", "https://")):
            yield target
        elif target.startswith(("[", "{")):
            try:
                pending = [json.loads(target)]
            except (ValueError, RecursionError):
                continue
            while pending:
                item = pending.pop()
                if isinstance(item, str) and item.startswith(("http://", "https://")):
                    yield item
                elif isinstance(item, dict):
                    pending.extend(reversed(list(item.values())))
                elif isinstance(item, list):
                    pending.extend(reversed(item))


def response_api_origins(body, mime, base_url=None):
    """Read observed endpoint data, including Nuxt tables and static JS; never run JS."""
    try:
        if mime == "application/json" or mime.endswith("+json"):
            documents = [json.loads(body)]
        elif mime in {"text/html", "application/xhtml+xml"}:
            parser = ApiConfigScripts()
            parser.feed(body.decode("utf-8"))
            documents = []
            for text in parser.documents:
                try:
                    documents.append(json.loads(text))
                except ValueError:
                    continue
            # Only observed embedded resources and literal script data grant new public origins.
            targets = [*parser.asset_urls]
            for text in parser.inline_scripts:
                targets.extend(javascript_url_literals(text))
            documents.extend({"observedResourceURL": urljoin(base_url, target) if base_url else target}
                             for target in targets)
        elif mime in {"application/javascript", "text/javascript"}:
            documents = ({"apiBaseURL": target} for target in javascript_url_literals(body.decode("utf-8")))
        else:
            return []
    except (ValueError, UnicodeError, RecursionError):
        return []
    result = []
    for document in documents:
        pending = [document]
        while pending:
            item = pending.pop()
            if isinstance(item, dict):
                for key, target in item.items():
                    if key.lower().replace("_", "") in {
                            "apibaseurl", "apibase", "apiurl", "observedresourceurl"}:
                        api_target = target
                        if type(target) is int and isinstance(document, list) and 0 <= target < len(document):
                            api_target = document[target]
                        if (isinstance(api_target, str) and len(api_target) <= 2048 and api_target.isascii()
                                and not re.search(r"[\x00-\x20\x7f\\]", api_target)):
                            try:
                                parsed, _, _ = http_authority(api_target, "EGRESS_URL_DENIED")
                            except (Denied, ValueError):
                                continue
                            if key == "observedResourceURL" or not parsed.query and not parsed.fragment:
                                origin = parsed.scheme + "://" + parsed.netloc
                                if origin not in result:
                                    result.append(origin)
                                    if len(result) == MAX_ROUTES:
                                        return result
                    if isinstance(target, (dict, list)):
                        pending.append(target)
            elif isinstance(item, list):
                pending.extend(entry for entry in item if isinstance(entry, (dict, list)))
    return result[:MAX_ROUTES]


def source_trees(source):
    """Parse literal embedded Python modules; decoding never imports or executes them."""
    text = source.decode("utf-8-sig") if isinstance(source, bytes) else source.lstrip("\ufeff")
    root = ast.parse(text)
    pending, seen, total = [(root, 0)], {digest(text.encode())}, len(text.encode())
    trees = []
    while pending and len(trees) < 8:
        tree, depth = pending.pop(0)
        trees.append(tree)
        if depth >= 2:
            continue
        modules, decoders = set(), set()
        parents = {child: node for node in ast.walk(tree) for child in ast.iter_child_nodes(node)}
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                modules.update(item.asname or item.name for item in node.names if item.name == "base64")
            elif isinstance(node, ast.ImportFrom) and node.module == "base64":
                decoders.update(item.asname or item.name for item in node.names if item.name == "b64decode")
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call) or len(node.args) != 1 or node.keywords:
                continue
            func = node.func
            decoder = (isinstance(func, ast.Name) and func.id in decoders
                       or isinstance(func, ast.Attribute) and func.attr == "b64decode"
                       and isinstance(func.value, ast.Name) and func.value.id in modules)
            if not decoder or not isinstance(node.args[0], ast.Constant):
                continue
            literal = node.args[0].value
            if not isinstance(literal, (str, bytes)) or len(literal) > 1398104:
                continue
            ancestor, excluded = node, False
            while ancestor in parents:
                ancestor = parents[ancestor]
                targets = ancestor.targets if isinstance(ancestor, ast.Assign) else (
                    [ancestor.target] if isinstance(ancestor, ast.AnnAssign) else [])
                if any(isinstance(target, ast.Name) and target.id.upper()
                       in {"META", "METADATA", "__METADATA__"} for target in targets):
                    excluded = True
            if excluded:
                continue
            try:
                decoded = base64.b64decode(literal, validate=True)
                if total + len(decoded) > 1048576 or digest(decoded) in seen:
                    continue
                embedded = ast.parse(decoded.decode("utf-8-sig"))
            except (ValueError, UnicodeError, SyntaxError):
                continue
            seen.add(digest(decoded))
            total += len(decoded)
            pending.append((embedded, depth + 1))
            if len(trees) + len(pending) >= 8:
                break
    return trees


def source_session_token_groups(source):
    """Bind declared guest issuers and consumers, independently for each token field."""
    groups, used = [], set()
    for tree in source_trees(source):
        constants, source_bases, ambiguous = {}, {}, set()
        for node in tree.body:
            targets = node.targets if isinstance(node, ast.Assign) else (
                [node.target] if isinstance(node, ast.AnnAssign) else [])
            if len(targets) != 1 or not isinstance(targets[0], ast.Name):
                continue
            try:
                constants[targets[0].id] = ast.literal_eval(node.value)
            except (ValueError, TypeError):
                continue
        pending = list(constants.values())
        while pending:
            value = pending.pop()
            if isinstance(value, dict):
                ident, base = value.get("id"), value.get("base")
                if isinstance(ident, str) and isinstance(base, str):
                    if ident in source_bases and source_bases[ident] != base:
                        ambiguous.add(ident)
                    source_bases[ident] = base
                pending.extend(item for item in value.values() if isinstance(item, (dict, list, tuple)))
            elif isinstance(value, (list, tuple)):
                pending.extend(item for item in value if isinstance(item, (dict, list, tuple)))
        for ident in ambiguous:
            source_bases.pop(ident, None)
        for cls in (node for node in ast.walk(tree) if isinstance(node, ast.ClassDef)):
            values = {node.targets[0].id: node.value.value for node in cls.body
                      if isinstance(node, ast.Assign) and len(node.targets) == 1
                      and isinstance(node.targets[0], ast.Name) and isinstance(node.value, ast.Constant)
                      and isinstance(node.value.value, str)}
            methods = [node for node in cls.body if isinstance(node, ast.FunctionDef)]
            header_methods, method_headers = {}, {}
            for method in methods:
                aliases = {}
                parents = {child: parent for parent in ast.walk(method) for child in ast.iter_child_nodes(parent)}
                for node in ast.walk(method):
                    if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
                        aliases.setdefault(node.targets[0].id, []).append(node)

                def branch_path(node):
                    path = []
                    while node in parents:
                        parent = parents[node]
                        if isinstance(parent, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)) and parent is not method:
                            path.append((parent, "scope"))
                        elif isinstance(parent, (ast.If, ast.For, ast.AsyncFor, ast.While, ast.Try,
                                                 ast.TryStar, ast.ExceptHandler, ast.Match, ast.match_case)):
                            for name, children in ast.iter_fields(parent):
                                if isinstance(children, list) and node in children:
                                    path.append((parent, name))
                        node = parent
                    return set(path)

                def fields_in(value, seen=frozenset()):
                    fields = {item.attr for item in ast.walk(value)
                              if isinstance(item, ast.Attribute) and isinstance(item.value, ast.Name)
                              and item.value.id == "self"}
                    for item in ast.walk(value):
                        if isinstance(item, ast.Name) and item.id not in seen and item.id in aliases:
                            definitions = [node for node in aliases[item.id]
                                           if (node.end_lineno, node.end_col_offset) < (item.lineno, item.col_offset)]
                            if definitions:
                                definition = max(definitions, key=lambda node: (node.end_lineno, node.end_col_offset))
                                if branch_path(definition).issubset(branch_path(item)):
                                    fields.update(fields_in(definition.value, seen | {item.id}))
                    return fields

                headers = {}
                for node in ast.walk(method):
                    if isinstance(node, ast.Dict):
                        for key, value in zip(node.keys, node.values):
                            if (isinstance(key, ast.Constant) and isinstance(key.value, str)
                                    and key.value.lower() == "authorization"):
                                headers[node] = fields_in(value)
                method_headers[method.name] = headers
                header_methods[method.name] = set().union(*headers.values()) if headers else set()
            token_fields = set().union(*header_methods.values()) if header_methods else set()
            tokens = {field: {"issuers": set(), "audiences": set()} for field in sorted(token_fields)}

            def literal_url(node):
                if isinstance(node, ast.Constant) and isinstance(node.value, str):
                    return node.value
                if isinstance(node, ast.Name):
                    return constants.get(node.id)
                if (isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name)
                        and node.value.id == "self"):
                    return values.get(node.attr)
                if isinstance(node, ast.Subscript):
                    container = literal_url(node.value)
                    try:
                        key = ast.literal_eval(node.slice)
                        if isinstance(container, dict) and isinstance(key, str):
                            return container.get(key)
                        if isinstance(container, (list, tuple)) and type(key) is int and 0 <= key < len(container):
                            return container[key]
                    except (ValueError, TypeError):
                        pass
                if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
                    left, right = literal_url(node.left), literal_url(node.right)
                    if isinstance(left, str) and isinstance(right, str) and len(left) + len(right) <= 2048:
                        return left + right
                return None

            for method in methods:
                writes = {item.attr for node in ast.walk(method)
                          if isinstance(node, (ast.Assign, ast.AnnAssign))
                          for target in (node.targets if isinstance(node, ast.Assign) else [node.target])
                          for item in ast.walk(target)
                          if isinstance(item, ast.Attribute) and isinstance(item.value, ast.Name)
                          and item.value.id == "self" and item.attr in token_fields}
                for node in ast.walk(method):
                    if not isinstance(node, ast.Call) or not node.args or not isinstance(node.func, ast.Attribute):
                        continue
                    source_id = literal_url(node.args[0])
                    wrapped = (isinstance(node.func.value, ast.Name) and node.func.value.id == "self"
                               and node.func.attr in method_headers and len(node.args) >= 2
                               and isinstance(source_id, str) and source_id in source_bases)
                    if not wrapped and node.func.attr not in {
                            "get", "post", "get_json", "post_json", "get_text", "post_form", "fetch"}:
                        continue
                    url = literal_url(node.args[1] if wrapped else node.args[0])
                    if wrapped and not isinstance(url, str):
                        url = source_bases[source_id]
                    if not isinstance(url, str):
                        continue
                    try:
                        parsed, _, _ = http_authority(url)
                    except (Denied, ValueError):
                        continue
                    origin = parsed.scheme + "://" + parsed.netloc
                    for field in writes:
                        tokens[field]["issuers"].add(origin)
                    for keyword in node.keywords:
                        if keyword.arg != "headers":
                            continue
                        fields = set()
                        for item in ast.walk(keyword.value):
                            fields.update(method_headers[method.name].get(item, set()))
                            if (isinstance(item, ast.Call) and isinstance(item.func, ast.Attribute)
                                    and isinstance(item.func.value, ast.Name) and item.func.value.id == "self"):
                                fields.update(header_methods.get(item.func.attr, set()))
                        for field in fields:
                            tokens[field]["audiences"].add(origin)
            for token in tokens.values():
                group = token["issuers"] | token["audiences"]
                if (token["issuers"] and token["audiences"] and 2 <= len(group) <= 8
                        and not used.intersection(group) and len(groups) < 4):
                    groups.append(sorted(group))
                    used.update(group)
    return groups


def source_cookie_declarations(source):
    """Literal Cookie headers belong to this source, never to the host's credentials."""
    try:
        trees = source_trees(source)
    except (SyntaxError, ValueError, UnicodeError):
        return {}
    candidates = []
    for node in (node for tree in trees for node in ast.walk(tree)):
        if isinstance(node, ast.Dict):
            candidates.extend(value for key, value in zip(node.keys, node.values)
                              if isinstance(key, ast.Constant) and isinstance(key.value, str)
                              and key.value.lower() == "cookie")
        elif isinstance(node, ast.Assign):
            if any(isinstance(target, ast.Subscript) and isinstance(target.slice, ast.Constant)
                   and isinstance(target.slice.value, str) and target.slice.value.lower() == "cookie"
                   for target in node.targets):
                candidates.append(node.value)
        elif isinstance(node, ast.FunctionDef) and node.name.lower().endswith(("cookie", "cookies")):
            for call in ast.walk(node):
                if (isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute)
                        and call.func.attr == "append" and len(call.args) == 1 and not call.keywords):
                    candidates.append(call.args[0])
    declared = {}
    for value in candidates:
        if (isinstance(value, ast.Constant) and isinstance(value.value, str) and "=" in value.value
                and len(value.value) <= 8192 and value.value.isascii()
                and not re.search(r"[\x00-\x1f\x7f]", value.value)):
            declared[value.value] = set()
    return declared


def source_application_headers(source):
    """Admit declared application field names, never transport or imported credentials."""
    try:
        trees = source_trees(source)
    except (SyntaxError, ValueError, UnicodeError):
        return set()

    def header_target(node):
        name = node.id if isinstance(node, ast.Name) else node.attr if isinstance(node, ast.Attribute) else ""
        return name.lower().endswith(("header", "headers"))

    roots, fields = [], set()
    for node in (node for tree in trees for node in ast.walk(tree)):
        if isinstance(node, ast.Dict):
            roots.extend(value for key, value in zip(node.keys, node.values)
                         if isinstance(key, ast.Constant) and isinstance(key.value, str)
                         and key.value.lower() in {"header", "headers"})
        elif isinstance(node, ast.Assign) and any(header_target(target) for target in node.targets):
            roots.append(node.value)
        elif isinstance(node, ast.AnnAssign) and header_target(node.target) and node.value is not None:
            roots.append(node.value)
        elif isinstance(node, ast.Call):
            roots.extend(item.value for item in node.keywords if item.arg == "headers")
            if (isinstance(node.func, ast.Attribute) and node.func.attr == "update"
                    and header_target(node.func.value)):
                roots.extend(node.args)
        elif isinstance(node, ast.Subscript) and header_target(node.value):
            if isinstance(node.slice, ast.Constant) and isinstance(node.slice.value, str):
                fields.add(node.slice.value.lower())
        elif isinstance(node, ast.FunctionDef) and node.name.lower().endswith("headers"):
            roots.append(node)
    for root in roots:
        for node in ast.walk(root):
            if isinstance(node, ast.Dict):
                fields.update(key.value.lower() for key in node.keys
                              if isinstance(key, ast.Constant) and isinstance(key.value, str))
    reserved = {"host", "content-length", "transfer-encoding", "connection", "keep-alive",
                "te", "trailer", "upgrade", "cookie", "cookie2", "set-cookie", "forwarded",
                "via", "x-real-ip", "x-http-method-override", "x-method-override"}
    return {name for name in fields if re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", name)
            and name not in reserved and not name.startswith(("proxy-", "x-forwarded-"))
            and not re.search(r"(?:authorization|authenticate|token|key|secret|password)$", name)}


def cookie_header_matches(header, expected):
    if not isinstance(header, str) or not expected or len(header) > 8192:
        return False
    if header == expected:
        return True
    supplied, current = SimpleCookie(), SimpleCookie()
    try:
        supplied.load(header)
        current.load(expected)
    except CookieError:
        return False
    # Requests may rebuild only the server-issued subset after a same-origin redirect.
    return bool(supplied) and all(
        name in current and morsel.value == current[name].value and not any(morsel.values())
        for name, morsel in supplied.items())


def cookie_state(header, expected, jar):
    parsed = []
    for value in (header, expected):
        cookies = SimpleCookie()
        try:
            if isinstance(value, str) and len(value) <= 8192:
                cookies.load(value)
        except CookieError:
            cookies = SimpleCookie()
        parsed.append(cookies)
    supplied, current = parsed
    return {
        "storedCount": len(jar), "expectedPresent": bool(expected),
        "suppliedParsed": bool(supplied), "expectedParsed": bool(current),
        "suppliedCount": len(supplied), "expectedCount": len(current),
        "matchingStoredCount": sum(any(cookie.name == name
            and cookie.value in {morsel.value, morsel.coded_value} for cookie in jar)
            for name, morsel in supplied.items()),
    }


def validate_host_credentials(value):
    require(isinstance(value, dict) and set(value) == {"origin", "cookie", "authorization"}
            and all(isinstance(item, str) for item in value.values()), "SOURCE_CREDENTIALS_INVALID")
    parsed, _, _ = http_authority(value["origin"], "SOURCE_CREDENTIALS_INVALID")
    require(value["origin"] == parsed.scheme + "://" + parsed.netloc
            and all(len(value[key]) <= 8192 and not re.search(r"[^\x20-\x7e]", value[key])
                    for key in ("cookie", "authorization"))
            and bool(value["cookie"] or value["authorization"]), "SOURCE_CREDENTIALS_INVALID")
    return copy.deepcopy(value)


class Broker:
    def __init__(self, policy, *, connector=socket.create_connection, allow_empty=False):
        self.policy = policy_from(policy, allow_empty=allow_empty)
        self.origin, self.host, self.port = http_authority(self.policy["origin"])
        self.addresses = tuple([*policy["pinnedIpv4"], *policy.get("pinnedIpv6", [])])
        self.connector = connector
        self.events = []
        self.count = 0
        self.total = 0
        self.next_request = 0
        self.deadline = None
        self.cancelled = None
        self.rate_limits = []
        self.cooldown_state = None
        self.network_observations = []
        self.network_observations_dropped = 0
        self.preferred_address = None
        self.lock = threading.RLock()
        self.cookies = http.cookiejar.CookieJar()
        self.cookie_domains = {self.host}
        self.observed_cookie_updates = {}
        self.session_tokens = []
        self.last_session_response = False
        self.last_http_observation = None
        self.source_sha256 = None
        self.source_cookies = {}
        self.source_headers = set()
        self.referer_origins = ()
        self.host_credentials = None
        self.runtime_gateway = None

    def bind_credentials(self, value):
        value = validate_host_credentials(value)
        if value["origin"] != self.policy["origin"]:
            return
        self.host_credentials = value
        if value["cookie"]:
            parsed = SimpleCookie()
            try:
                parsed.load(value["cookie"])
            except CookieError:
                raise Denied("SOURCE_CREDENTIALS_INVALID") from None
            require(bool(parsed) and len(parsed) <= 32 and all(
                not any(morsel.values()) for morsel in parsed.values()), "SOURCE_CREDENTIALS_INVALID")
            for name, morsel in parsed.items():
                self.cookies.set_cookie(http.cookiejar.Cookie(
                    0, name, morsel.coded_value, None, False, self.host, False, False,
                    "/", True, self.origin.scheme == "https", None, True, None, None, {}))

    def fetch(self, value):
        with self.lock:
            result = self._fetch(value)
            return self.runtime_gateway.fetch(self, value, result) if self.runtime_gateway else result

    def bind_execution(self, deadline, cancelled):
        self.deadline, self.cancelled = deadline, cancelled

    def bind_source(self, source):
        identity = digest(source)
        require(self.source_sha256 in {None, identity}, "EGRESS_POLICY_INVALID")
        if self.source_sha256 is None:
            self.source_cookies = source_cookie_declarations(source)
            self.source_headers = source_application_headers(source)
            self.source_sha256 = identity

    def accept_source_cookie(self, header):
        require_header(isinstance(header, str), "value_type", "cookie")
        if header not in self.source_cookies:
            return False
        origin = self.policy["origin"]
        seeded = self.source_cookies[header]
        if origin not in seeded:
            parsed = SimpleCookie()
            try:
                parsed.load(header)
            except CookieError:
                require_header(False, "cookie_format", "cookie")
            require_header(parsed and len(parsed) <= 32 and all(
                not any(morsel.values()) for morsel in parsed.values()), "cookie_format", "cookie")
            existing = {cookie.name for cookie in self.cookies}
            require_header(len(self.cookies) + len(set(parsed) - existing) <= 32,
                           "cookie_capacity", "cookie")
            for name, morsel in parsed.items():
                if name not in existing:
                    self.cookies.set_cookie(http.cookiejar.Cookie(
                        0, name, morsel.coded_value, None, False, self.host, False, False,
                        "/", True, self.origin.scheme == "https", None, True, None, None, {}))
            # Each declared origin seeds independently; server state never crosses origins.
            seeded.add(origin)
        return True

    def wait_for_slot(self):
        self.snapshot_cooldown("before_wait")
        require(self.cancelled is None or not self.cancelled(), "EGRESS_CANCELLED")
        delay = max(0, self.next_request - time.monotonic())
        require(self.deadline is None or time.monotonic() + delay < self.deadline,
                "EGRESS_COOLDOWN_BUDGET")
        if self.cancelled is None:
            time.sleep(delay)
        else:
            while delay > 0:
                time.sleep(min(delay, 0.05))
                require(not self.cancelled(), "EGRESS_CANCELLED")
                require(self.deadline is None or time.monotonic() < self.deadline, "EGRESS_HTTP_DEADLINE")
                delay = max(0, self.next_request - time.monotonic())

    def snapshot_cooldown(self, phase="receipt"):
        if self.cooldown_state is None:
            return
        started, seconds, record = self.cooldown_state
        now = time.monotonic()
        remaining = None if seconds is None else max(0, seconds * 1000 - int(max(0, now - started) * 1000))
        budget = None if self.deadline is None else max(0, int((self.deadline - now) * 1000))
        record["cooldownContext"] = {
            "phase": phase, "recordedAtEpochMilliseconds": int(time.time() * 1000),
            "remainingWaitMilliseconds": remaining, "taskBudgetRemainingMilliseconds": budget,
            "fitsTaskBudget": None if remaining is None or budget is None else remaining < budget}

    def raise_rate_limit(self, url, header):
        response_time = time.time()
        timing = retry_after(header, response_time)
        seconds = timing["seconds"]
        if seconds is not None:
            self.next_request = max(self.next_request, time.monotonic() + seconds)
        record = {"responseStatus": 429, "retryAfter": timing, "automaticRetry": False,
                  "requestNumber": self.count, "requestUrlSha256": digest(url.encode()),
                  "observedAtEpochMilliseconds": int(response_time * 1000)}
        self.rate_limits.append(record)
        self.cooldown_state = (time.monotonic(), seconds, record)
        self.snapshot_cooldown("at_response")
        raise Denied("EGRESS_RATE_LIMITED", retry_after=timing)

    def record_network_failure(self, url, index, phase, error, started):
        if len(self.network_observations) >= 32:
            self.network_observations_dropped += 1
            return
        kind = ("certificate" if isinstance(error, ssl.SSLCertVerificationError) else
                "timeout" if isinstance(error, TimeoutError) else
                "tls" if isinstance(error, ssl.SSLError) else
                "connection_refused" if isinstance(error, ConnectionRefusedError) else
                "connection_reset" if isinstance(error, ConnectionResetError) else
                "http_protocol" if isinstance(error, http.client.HTTPException) else
                "invalid_response" if isinstance(error, ValueError) else "io")
        number = getattr(error, "errno", None)
        self.network_observations.append({
            "requestUrlSha256": digest(url.encode()), "requestNumber": self.count,
            "addressIndex": index, "stage": phase, "kind": kind,
            "errno": number if type(number) is int and -1 <= number <= 65535 else None,
            "elapsedMilliseconds": round((time.monotonic() - started) * 1000)})

    def connect(self, url, deadline, started):
        addresses = list(self.addresses)
        if self.preferred_address in addresses:
            addresses.remove(self.preferred_address)
            addresses.insert(0, self.preferred_address)
        context = ssl.create_default_context() if self.origin.scheme == "https" else None
        for index, ip in enumerate(addresses):
            require(self.cancelled is None or not self.cancelled(), "EGRESS_CANCELLED")
            remaining = deadline - time.monotonic()
            require(remaining > 0, "EGRESS_HTTP_DEADLINE")
            if context is not None:
                connection = http.client.HTTPSConnection(self.host, self.port, timeout=min(5, remaining),
                                                          context=context)
            else:
                connection = http.client.HTTPConnection(self.host, self.port, timeout=min(5, remaining))
            phase = "tcp_connect"

            def pinned(address, timeout=5, source_address=None):
                nonlocal phase
                require(address == (self.host, self.port) and source_address is None,
                        "EGRESS_CONNECT_MISMATCH")
                sock = self.connector((ip, self.port), timeout=min(timeout, max(0.001, deadline - time.monotonic())))
                try:
                    peer = sock.getpeername()
                    require(ipaddress.ip_address(peer[0]) == ipaddress.ip_address(ip)
                            and peer[1] == self.port, "EGRESS_PEER_MISMATCH")
                    if context is not None:
                        phase = "tls_handshake"
                    return sock
                except BaseException:
                    sock.close()
                    raise

            # Pinned sockets retain the original Host and, for HTTPS, SNI and certificate checks.
            connection._create_connection = pinned
            try:
                connection.connect()
                self.preferred_address = ip
                return connection, self.addresses.index(ip)
            except (OSError, ValueError, http.client.HTTPException) as error:
                self.record_network_failure(url, self.addresses.index(ip), phase, error, started)
                connection.close()
                if index == len(addresses) - 1:
                    raise
            except BaseException:
                connection.close()
                raise

    def _fetch(self, value):
        self.last_session_response = False
        self.last_http_observation = None
        self.observed_cookie_updates = {}
        value = copy.deepcopy(value)
        require(isinstance(value, dict) and isinstance(value.get("headers"), dict)
                and all(isinstance(key, str) for key in value["headers"]), "EGRESS_REQUEST_INVALID")
        provided = [key for key in value["headers"] if key.lower() == "cookie"]
        supplied_cookies = [value["headers"][key] for key in provided]
        for key in provided:
            del value["headers"][key]
        supplied_authorization = [value["headers"].pop(key) for key in list(value["headers"])
                                  if key.lower() == "authorization"]
        # Explicit administrator state overrides stale headers embedded in the source.
        if self.host_credentials:
            if self.host_credentials["cookie"]:
                supplied_cookies = []
            if self.host_credentials["authorization"]:
                supplied_authorization = []
        parsed, body = validate_request(value, self.policy, source_headers=self.source_headers,
                                        session_tokens=self.session_tokens,
                                        referer_origins=self.referer_origins)
        valid_authorization = (not supplied_authorization or len(supplied_authorization) == 1
                and isinstance(supplied_authorization[0], str)
                and (supplied_authorization[0] == "" or supplied_authorization[0] in self.session_tokens
                     or "Bearer " + supplied_authorization[0] in self.session_tokens))
        if not valid_authorization:
            error = Denied("EGRESS_SESSION_TOKEN_DENIED")
            error.request_diagnostic = {
                "kind": "header", "reason": "session_token_not_observed", "headerName": "authorization",
                "requestUrlSha256": digest(value["url"].encode("ascii")),
                "requestOriginSha256": digest(self.policy["origin"].encode("ascii"))}
            raise error
        cookie_request = Request(value["url"])
        self.cookies.clear_expired_cookies()
        require_header(len(supplied_cookies) <= 1, "duplicate_name", "cookie")
        declared_cookie = bool(supplied_cookies and self.accept_source_cookie(supplied_cookies[0]))
        self.cookies.add_cookie_header(cookie_request)
        expected_cookie = cookie_request.get_header("Cookie", "")
        try:
            require_header(not supplied_cookies or supplied_cookies[0] == "" or declared_cookie
                           or cookie_header_matches(supplied_cookies[0], expected_cookie),
                           "cookie_not_observed", "cookie")
        except Denied as error:
            error.request_diagnostic["cookieState"] = cookie_state(
                supplied_cookies[0], expected_cookie, self.cookies)
            raise
        self.lightweight_cookie_skip = None
        self.private_gateway_cookie_names = set()
        trusted_headers = (self.runtime_gateway.request_headers(self, value, expected_cookie)
                           if self.runtime_gateway and not any(supplied_authorization) and not declared_cookie else {})
        require(self.count < self.policy["maxRequests"], "EGRESS_REQUEST_BUDGET")
        if self.total >= self.policy["maxTotalResponseBytes"]:
            raise byte_budget_error(self.policy, self.total, 0, 0, "before_request", "total_response_bytes")
        self.wait_for_slot()
        self.count += 1
        self.next_request = time.monotonic() + self.policy["minIntervalSeconds"]
        started = time.monotonic()
        # Allow slower responses without extending the source's overall execution deadline.
        deadline = started + 30 + 5 * (len(self.addresses) - 1)
        if self.deadline is not None:
            deadline = min(deadline, self.deadline)
        connection = None
        address_index = None
        phase = "connect"
        expired = threading.Event()

        def abort():
            expired.set()
            sock = connection.sock if connection is not None else None
            if sock is not None:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                sock.close()

        timer = threading.Timer(max(0, deadline - time.monotonic()), abort)
        timer.daemon = True
        timer.start()
        try:
            connection, address_index = self.connect(value["url"], deadline, started)
            require(not expired.is_set(), "EGRESS_HTTP_DEADLINE")
            if connection.sock is not None:
                connection.sock.settimeout(max(0.001, deadline - time.monotonic()))
            headers = {**value["headers"], "Accept-Encoding": "identity", "Connection": "close"}
            if expected_cookie:
                headers["Cookie"] = expected_cookie
            if supplied_authorization:
                headers["Authorization"] = supplied_authorization[0]
            if self.host_credentials and self.host_credentials["authorization"]:
                headers["Authorization"] = self.host_credentials["authorization"]
            if trusted_headers:
                headers = {key: val for key, val in headers.items()
                           if key.lower() not in {"cookie", "user-agent"}}
                headers.update(trusted_headers)
            target = parsed.path + ("?" + parsed.query if parsed.query else "")
            phase = "request_send"
            connection.request(value["method"], target, body=body or None, headers=headers)
            phase = "response_headers"
            response = connection.getresponse()
            content_type = response_content_type(response)
            self.last_http_observation = {
                "method": value["method"], "requestUrlSha256": digest(value["url"].encode()),
                "requestNumber": self.count, "responseStatus": response.status,
                "responseContentType": content_type}
            if response.status == 429:
                self.raise_rate_limit(value["url"], response.getheader("Retry-After"))
            redirected = response.status in {301, 302, 303, 307, 308}
            require(not 300 <= response.status < 400 or redirected, "EGRESS_REDIRECT_DENIED")
            location = response.getheader("Location") if redirected else None
            require(location is None or isinstance(location, str) and len(location) <= 2048
                    and not re.search(r"[\x00-\x1f\x7f]", location), "EGRESS_REDIRECT_DENIED")
            require(response.getheader("Content-Encoding", "identity").lower() in {"", "identity"},
                    "EGRESS_CONTENT_ENCODING_DENIED")
            ranged = any(key.lower() == "range" for key in value["headers"])
            error_status = 400 <= response.status <= 599
            head_missing = value["method"] == "HEAD" and response.status == 404
            textual_error = (content_type.startswith("text/") or content_type == "application/json"
                             or content_type.endswith("+json"))
            require(200 <= response.status < 300 or redirected or error_status, "EGRESS_HTTP_STATUS_DENIED")
            total_before = self.total
            remaining = self.policy["maxTotalResponseBytes"] - total_before
            limit = min(self.policy["maxResponseBytes"], remaining)
            budget_reason = ("total_response_bytes" if remaining <= self.policy["maxResponseBytes"]
                             else "single_response_bytes")
            if ranged:
                requested_range = next(val for key, val in value["headers"].items() if key.lower() == "range")
                range_start, range_end = byte_range(requested_range)
                range_limit = range_end - range_start + 1
                if range_limit < limit:
                    limit, budget_reason = range_limit, "range_response_bytes"
            # A server may ignore Range; retain only the permitted prefix, not the full body.
            prefix_only = ranged and response.status == 200
            declared_length = response.getheader("Content-Length")
            if declared_length is not None:
                require(re.fullmatch(r"[0-9]{1,20}", declared_length) is not None,
                        "EGRESS_CONTENT_LENGTH_INVALID")
                if int(declared_length) > limit and not prefix_only and value["method"] != "HEAD":
                    raise byte_budget_error(self.policy, total_before, limit, int(declared_length),
                                            "content_length", budget_reason)
            phase = "response_body"
            result = response.read(limit if prefix_only else limit + 1)
            require(not expired.is_set(), "EGRESS_HTTP_DEADLINE")
            self.total += len(result)
            if len(result) > limit:
                raise byte_budget_error(self.policy, total_before, limit, len(result), "body_read", budget_reason)
            response_complete = (value["method"] == "HEAD" or
                                 (len(result) == int(declared_length) if declared_length is not None
                                  else not prefix_only or len(result) < limit))
            resource_complete = False
            if prefix_only:
                resource_complete = response_complete
            if ranged and response.status == 206:
                content_range = response.getheader("Content-Range", "")
                match = re.fullmatch(r"bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})", content_range)
                require(match is not None, "EGRESS_RANGE_INVALID")
                start, end, total = map(int, match.groups())
                require(start == range_start and start <= end <= range_end
                        and end < total <= 2 ** 63 - 1 and end - start + 1 == len(result),
                        "EGRESS_RANGE_INVALID")
                resource_complete = start == 0 and end + 1 == total
                response_complete = True
            selected = {key: val for key, val in response.getheaders()
                        if key.lower() in {"content-type", "content-length", "content-range", "date", "cf-mitigated"}}
            if location is not None:
                selected["Location"] = location
            cookie_updates = {}
            accepted_cookie_headers = 0
            observed_names = SimpleCookie()
            if trusted_headers:
                observed_names.load(expected_cookie)
                # Imported names remain private even if an older public cookie had
                # the same name. Never copy the gateway's rotation into the source jar.
                for name in self.private_gateway_cookie_names:
                    observed_names.pop(name, None)
                self.runtime_gateway.update_cookies(self, value, response.getheaders())
            for key, val in response.getheaders():
                if (key.lower() != "set-cookie" or len(val) > 4096 or "\r" in val or "\n" in val
                        or accepted_cookie_headers >= 32):
                    continue
                if trusted_headers:
                    named = SimpleCookie()
                    try:
                        named.load(val)
                    except CookieError:
                        continue
                    if not named or any(name not in observed_names for name in named):
                        continue
                cookie_headers = Message()
                cookie_headers.add_header("Set-Cookie", val)
                before = {(cookie.domain, cookie.path, cookie.name) for cookie in self.cookies}
                received = self.cookies.make_cookies(SimpleNamespace(info=lambda: cookie_headers), cookie_request)
                removed = before - {(cookie.domain, cookie.path, cookie.name) for cookie in self.cookies}
                # CookieJar applies expired Set-Cookie by removing state, not returning a cookie.
                def allowed_domain(domain):
                    domain = domain.lstrip(".")
                    return domain == self.host or (domain in self.cookie_domains
                                                   and self.host.endswith("." + domain))

                if not received and removed and all(allowed_domain(domain)
                                                    for domain, _, _ in removed):
                    for scope in removed:
                        cookie_updates[scope] = val
                        self.observed_cookie_updates[scope] = None
                    accepted_cookie_headers += 1
                    continue
                # Parent domains must themselves belong to this job's registered origins.
                if not received or any(not allowed_domain(cookie.domain) for cookie in received):
                    continue
                existing = {(cookie.domain, cookie.path, cookie.name) for cookie in self.cookies}
                if len(existing | {(cookie.domain, cookie.path, cookie.name) for cookie in received}) > 32:
                    continue
                for cookie in received:
                    self.cookies.set_cookie(cookie)
                    cookie_updates[(cookie.domain, cookie.path, cookie.name)] = val
                    self.observed_cookie_updates[(cookie.domain, cookie.path, cookie.name)] = copy.copy(cookie)
                self.cookies.clear_expired_cookies()
                accepted_cookie_headers += 1
            # Only the final update per scope reaches Requests; an earlier setter must not
            # resurrect a cookie that a later header in this response has already deleted.
            set_cookies = list(dict.fromkeys(cookie_updates.values()))
            lowered = result.lower()
            grant = self.policy.get("sessionToken")
            if (grant and (grant.get("responseScope") == "origin" or value["url"] == grant["requestUrl"])
                    and 200 <= response.status < 300 and response.status != 206
                    and response_complete
                    and (content_type == "application/json" or content_type.endswith("+json"))):
                tokens = response_session_tokens(result, grant)
                self.last_session_response = bool(tokens)
                if (not supplied_authorization or supplied_authorization == [""]) and not any(
                        key.lower() == "token" and val for key, val in value["headers"].items()):
                    for bearer in tokens:
                        self.session_tokens[:] = [item for item in self.session_tokens if item != bearer][-3:]
                        self.session_tokens.append(bearer)
            markers = {"routerData": b"_router_data", "nextData": b"__next_data__",
                       "reactRouter": b"reactrouter", "turnstile": b"turnstile",
                       "cfChallenge": b"cf-chl-", "captcha": b"captcha"}
            self.events.append({"method": value["method"], "pathSha256": digest(parsed.path.encode()),
                                "requestUrlSha256": digest(value["url"].encode()),
                                "requestBodyBytes": len(body), "responseStatus": response.status,
                                "responseBytes": len(result), "responseSha256": digest(result),
                                "requestCookiePresent": bool(expected_cookie or trusted_headers),
                                "receivedSetCookieCount": len(set_cookies),
                                "responseComplete": response_complete,
                                "responseContentType": content_type,
                                "responseClass": ("http_redirect" if redirected else
                                                  "head_not_found" if head_missing else
                                                  ("textual_server_error" if textual_error else "server_error")
                                                  if response.status >= 500 else
                                                  ("textual_client_error" if textual_error else "client_error")
                                                  if error_status else
                                                  "partial_content" if response.status == 206 else
                                                  "http_200" if response.status == 200 else "http_2xx"),
                                "connectedPublicIpv4": self.addresses[address_index],
                                "connectedAddressIndex": address_index,
                                "contentMarkers": {key: marker in lowered for key, marker in markers.items()},
                                "windowAssignments": sorted(set(
                                    match.decode("ascii") for match in re.findall(
                                        rb"window\.([A-Za-z_$][A-Za-z0-9_$]{0,39})\s*=", result)))[:12],
                                "elapsedMilliseconds": round((time.monotonic() - started) * 1000)})
            if trusted_headers:
                self.events[-1]['lightweightCookieReuse'] = 'attempted'
            elif self.lightweight_cookie_skip:
                self.events[-1]['lightweightCookieSkip'] = self.lightweight_cookie_skip
            if ranged:
                prefix = result.lstrip()
                signature = ("hls" if prefix.startswith(b"#EXTM3U") else
                             "mp4" if len(result) >= 12 and result[4:8] == b"ftyp" else
                             "mpeg_ts" if len(result) >= 377 and all(result[offset] == 0x47
                                                                    for offset in (0, 188, 376)) else
                             "error_document" if prefix[:1] in {b"<", b"{", b"["} else "unknown")
                self.events[-1].update(rangeRequested=True, prefixFormat=signature,
                                       resourceComplete=resource_complete)
            return {"status": response.status, "headers": selected,
                    "body": base64.b64encode(result).decode("ascii"), "setCookies": set_cookies}
        except (OSError, ValueError, http.client.HTTPException) as error:
            if address_index is not None:
                self.record_network_failure(value["url"], address_index, phase, error, started)
            raise
        finally:
            timer.cancel()
            if connection is not None:
                connection.close()


class Routes:
    """Declared origins share one budget; exact-URL routes remain exact unless opted in."""

    def __init__(self, policy, *, connector=socket.create_connection):
        self.policy = copy.deepcopy(policy_from(policy))
        require(self.policy["schemaVersion"] == 3, "EGRESS_POLICY_INVALID")
        self.routes = {route["origin"]: Broker(
            route_policy(self.policy, copy.deepcopy(route)), connector=connector, allow_empty=True)
            for route in self.policy["routes"]}
        self.share_session_tokens()
        self.connector = connector
        self.runtime_gateway = None
        self.deadline, self.cancelled, self.source = None, None, None
        self.pending_failures = {}
        self.events = []
        self.count = self.total = self.next_request = 0
        self.rate_limits = []
        self.cooldown_state = None
        self.dns_observations = []
        self.dns_observations_dropped = 0
        self.network_observations = []
        self.network_observations_dropped = 0
        self.lock = threading.RLock()
        self.resource_documents = set()
        self.player_resources = {}
        self.resource_responses = {}
        self.source_call = None
        self.last_redirect = None
        self.last_http_observation = None
        self.last_dns_observation = None
        self.api_origins = {}
        self.host_credentials = None
        self.server_cookies = {}

    def prepare_cookie_state(self, broker, value):
        broker.cookie_domains = {route.host for route in self.routes.values()}
        broker.cookies.clear_expired_cookies()
        for scope, cookie in list(self.server_cookies.items()):
            if cookie.is_expired():
                del self.server_cookies[scope]
                continue
            domain = cookie.domain.lstrip(".")
            matches = (broker.host == domain or cookie.domain_specified
                       and broker.host.endswith("." + domain))
            if matches:
                existing = {(item.domain, item.path, item.name) for item in broker.cookies}
                if scope in existing or len(existing) < 32:
                    broker.cookies.set_cookie(copy.copy(cookie))

        headers = value.get("headers")
        if not isinstance(headers, dict):
            return value
        keys = [key for key in headers if isinstance(key, str) and key.lower() == "cookie"]
        if len(keys) != 1 or not isinstance(headers[keys[0]], str) or len(headers[keys[0]]) > 8192:
            return value
        supplied, expected = SimpleCookie(), SimpleCookie()
        try:
            supplied.load(headers[keys[0]])
            request = Request(value["url"])
            broker.cookies.add_cookie_header(request)
            expected.load(request.get_header("Cookie", ""))
        except (CookieError, ValueError):
            return value
        removed = []
        for name, morsel in supplied.items():
            if any(morsel.values()) or (name in expected and expected[name].value == morsel.value):
                continue
            if any(cookie.name == name and cookie.value in {morsel.value, morsel.coded_value}
                   for cookie in self.server_cookies.values()):
                removed.append(name)
        if not removed:
            return value
        # Requests can carry a previously observed cookie beyond its host/path scope.
        # Reconstruct the outbound header instead of forwarding it or aborting the source.
        for name in removed:
            del supplied[name]
        value = copy.deepcopy(value)
        value["headers"][keys[0]] = "; ".join(item.OutputString() for item in supplied.values())
        return value

    def commit_cookie_state(self, broker):
        for scope, cookie in broker.observed_cookie_updates.items():
            if cookie is not None:
                self.server_cookies[scope] = cookie
                continue
            self.server_cookies.pop(scope, None)
            for route in self.routes.values():
                try:
                    route.cookies.clear(*scope)
                except KeyError:
                    pass

    def bind_credentials(self, value):
        self.host_credentials = validate_host_credentials(value)
        for broker in self.routes.values():
            broker.bind_credentials(self.host_credentials)

    def share_session_tokens(self, added=None):
        for group in self.policy.get("sessionTokenGroups", []):
            tokens = next((self.routes[origin].session_tokens for origin in group
                           if origin in self.routes and origin != added), [])
            for origin in group:
                if origin in self.routes:
                    self.routes[origin].session_tokens = tokens

    def snapshot_resources(self):
        """Job-local completed text responses, newest first; no extra network reads."""
        with self.lock:
            return [(url, copy.deepcopy(response))
                    for url, response in reversed(self.resource_responses.items())]

    def set_source_call(self, call):
        with self.lock:
            self.source_call = copy.deepcopy(call)

    def snapshot_cooldown(self, phase="receipt"):
        with self.lock:
            Broker.snapshot_cooldown(self, phase)

    def record_dns(self, observation, origin, *, cached=False):
        row = copy.deepcopy(observation)
        row.update(originSha256=digest(origin.encode()), cacheHit=cached)
        if cached:
            row.pop("elapsedMilliseconds", None)
        if self.source_call is not None:
            row["observedDuringCall"] = copy.deepcopy(self.source_call)
        self.last_dns_observation = row
        if len(self.dns_observations) < 64:
            self.dns_observations.append(row)
        else:
            self.dns_observations_dropped += 1

    def resolve_public_origin(self, host, port, origin):
        observations = []
        try:
            return public_dns_addresses(host, port=port, observations=observations)
        finally:
            for observation in observations:
                self.record_dns(observation, origin)

    def fetch_resource(self, url, parent=None):
        """Host-only public asset reads; never grant these addresses to the Spider."""
        with self.lock:
            entries = self.policy.get("resourceEvidence", {}).get("entryUrls", [])
            require(isinstance(url, str) and isinstance(parent, (str, type(None))),
                    "EGRESS_URL_DENIED")
            parsed = urlsplit(url)
            if parent is None:
                require(url in entries, "EGRESS_URL_DENIED")
            else:
                previous = urlsplit(parent)
                require(parent in self.resource_documents
                        and (parsed.scheme, parsed.netloc) == (previous.scheme, previous.netloc)
                        and parsed.path.lower().endswith((".js", ".mjs")),
                        "EGRESS_URL_DENIED")
            broker = self.routes.get(parsed.scheme + "://" + parsed.netloc)
            require(broker is not None, "EGRESS_URL_DENIED")
            original = broker.policy["exactUrls"]
            broker.policy["exactUrls"] = [url]
            try:
                response = self._fetch({"method": "GET", "url": url, "body": "", "headers": {}})
            finally:
                broker.policy["exactUrls"] = original
            if response["status"] == 200 and self.events[-1]["responseComplete"]:
                self.resource_documents.add(url)
            return response

    def bind_execution(self, deadline, cancelled):
        self.deadline, self.cancelled = deadline, cancelled
        for broker in self.routes.values():
            broker.bind_execution(deadline, cancelled)

    def bind_source(self, source):
        self.source = source
        declarations = None
        for broker in self.routes.values():
            broker.bind_source(source)
            if declarations is None:
                declarations = broker.source_cookies
            else:
                broker.source_cookies = declarations

    def snapshot_urls(self):
        """Trusted owner only: copy exact addresses, not action budgets or observations."""
        return {route["origin"]: tuple(self.routes[route["origin"]].policy["exactUrls"])
                for route in self.policy["routes"]}

    def restore_urls(self, snapshot):
        require(isinstance(snapshot, dict) and set(snapshot) == set(self.routes), "EGRESS_POLICY_INVALID")
        candidate = copy.deepcopy(self.policy)
        for route in candidate["routes"]:
            urls = snapshot[route["origin"]]
            require(type(urls) is tuple and set(route["exactUrls"]) <= set(urls), "EGRESS_POLICY_INVALID")
            route["exactUrls"] = list(urls)
        policy_from(candidate)
        for route in candidate["routes"]:
            self.routes[route["origin"]].policy["exactUrls"] = route["exactUrls"]

    def fetch(self, value):
        with self.lock:
            return self._fetch(value)

    def register_player_resource(self, url, headers):
        """Host loader only: grant the validated direct player URL, not a whole CDN origin."""
        mode = self.policy.get("playerResources")
        if mode is None:
            return
        parsed, host, port = http_authority(url, "EGRESS_URL_DENIED")
        require(parsed.scheme == "https" or mode == "public_http_https", "EGRESS_URL_DENIED")
        origin = parsed.scheme + "://" + parsed.netloc
        with self.lock:
            if origin not in self.routes:
                require(len(self.routes) < MAX_ROUTES, "EGRESS_REQUEST_BUDGET")
                addresses = self.resolve_public_origin(host, port, origin)
                header_origins = []
                for key, value in headers.items():
                    if key.lower() in {"origin", "referer"}:
                        target = urlsplit(value)
                        header_origin = target.scheme + "://" + target.netloc
                        if header_origin in self.routes and header_origin not in header_origins:
                            header_origins.append(header_origin)
                route = {"origin": origin, "exactUrls": [url], **address_pins(addresses),
                         "headerOrigins": header_origins}
                broker = Broker(route_policy(self.policy, route), connector=self.connector, allow_empty=True)
                broker.bind_execution(self.deadline, self.cancelled)
                if self.source is not None:
                    broker.bind_source(self.source)
                if self.host_credentials:
                    broker.bind_credentials(self.host_credentials)
                self.routes[origin] = broker
            else:
                self.register_url(url, origin)
            self.player_resources[url] = dict(headers)

    def _fetch(self, value):
        self.last_http_observation = None
        self.last_dns_observation = None
        require(isinstance(value, dict) and isinstance(value.get("url"), str), "EGRESS_REQUEST_INVALID")
        try:
            parsed = urlsplit(value["url"])
        except ValueError:
            error = Denied("EGRESS_URL_DENIED")
            error.request_diagnostic = url_denial_diagnostic(value["url"], value.get("method"))
            raise error from None
        require(not parsed.hostname or public_host(parsed.hostname), "EGRESS_NONPUBLIC_TARGET_DENIED")
        origin = parsed.scheme + "://" + parsed.netloc
        broker = self.routes.get(origin)
        if broker is None and origin in self.policy.get("pendingOrigins", []):
            if origin in self.pending_failures:
                code, observation = self.pending_failures[origin]
                if observation is not None:
                    self.record_dns(observation, origin, cached=True)
                raise Denied(code, dns_observation=self.last_dns_observation)
            _, host, port = http_authority(origin)
            try:
                addresses = self.resolve_public_origin(host, port, origin)
            except Denied as error:
                if self.last_dns_observation is None and error.dns_observation is not None:
                    self.record_dns(error.dns_observation, origin)
                self.pending_failures[origin] = (error.code, copy.deepcopy(error.dns_observation))
                raise
            route = {"origin": origin, "exactUrls": [], "requestScope": "origin",
                     **address_pins(addresses), "sessionToken": {"responseScope": "origin"}}
            broker = Broker(route_policy(self.policy, route), connector=self.connector, allow_empty=True)
            broker.bind_execution(self.deadline, self.cancelled)
            if self.source is not None:
                broker.bind_source(self.source)
            if self.host_credentials:
                broker.bind_credentials(self.host_credentials)
            self.routes[origin] = broker
            self.share_session_tokens(added=origin)
        api_grant = None
        if broker is None and origin in self.api_origins:
            issuer, response_sha = self.api_origins[origin]
            self.register_public_origin(origin, issuer)
            broker = self.routes.get(origin)
            api_grant = {"issuerOriginSha256": digest(issuer.policy["origin"].encode()),
                         "responseSha256": response_sha, "targetOriginSha256": digest(origin.encode())}
            self.api_origins.pop(origin, None)
        if broker is None:
            error = Denied("EGRESS_URL_DENIED")
            error.request_diagnostic = url_denial_diagnostic(value["url"], value.get("method"))
            raise error
        # Origin-scoped routes belong to this job and already have approved public pins.
        broker.referer_origins = tuple(
            origin for origin, route in self.routes.items()
            if route.policy.get("requestScope") == "origin")
        value = self.prepare_cookie_state(broker, value)
        broker.runtime_gateway = self.runtime_gateway
        broker.count, broker.total, broker.next_request = self.count, self.total, self.next_request
        broker.cooldown_state = self.cooldown_state
        before = len(broker.events)
        rates_before = len(broker.rate_limits)
        network_before = len(broker.network_observations)
        dropped_before = broker.network_observations_dropped
        try:
            try:
                response = broker.fetch(value)
            except Denied as error:
                if error.code == "EGRESS_URL_DENIED":
                    error.request_diagnostic = registered_query_separator_mismatch(
                        value.get("url"), value.get("method"), broker.policy) or url_denial_diagnostic(
                            value.get("url"), value.get("method"), broker.policy)
                raise
        finally:
            self.commit_cookie_state(broker)
            self.count, self.total, self.next_request = broker.count, broker.total, broker.next_request
            self.cooldown_state = broker.cooldown_state
            events = broker.events[before:]
            self.events.extend(events)
            self.last_http_observation = copy.deepcopy(broker.last_http_observation)
            rates = broker.rate_limits[rates_before:]
            observed = broker.network_observations[network_before:]
            if self.source_call is not None:
                for row in [*events, *rates, *observed]:
                    row["observedDuringCall"] = copy.deepcopy(self.source_call)
            self.rate_limits.extend(rates)
            available = 32 - len(self.network_observations)
            self.network_observations.extend(observed[:available])
            self.network_observations_dropped += (max(0, len(observed) - available)
                                                + broker.network_observations_dropped - dropped_before)
        if api_grant is not None:
            self.events[-1]["apiOriginObservation"] = api_grant
        if response["status"] in {301, 302, 303, 307, 308}:
            self.register_redirect(value["url"], response, broker)
        mime = next((v.split(";", 1)[0].strip().lower()
                     for k, v in response["headers"].items() if k.lower() == "content-type"), "")
        if value["method"] in {"GET", "POST"} and self.events[-1]["responseComplete"]:
            json_response = mime == "application/json" or mime.endswith("+json")
            if value["method"] == "POST" and not json_response:
                json_response = base64.b64decode(response["body"], validate=True).lstrip().startswith((b"{", b"["))
            if (not broker.last_session_response and (json_response or value["method"] == "GET" and (mime.startswith("text/")
                    or mime in {"application/json", "application/javascript", "application/xhtml+xml"}
                    or mime.endswith("+json")))):
                self.resource_responses.pop(value["url"], None)
                self.resource_responses[value["url"]] = copy.deepcopy(response)
                self.resource_responses[value["url"]]["requestMethod"] = value["method"]
                if self.source_call is not None:
                    self.resource_responses[value["url"]]["observedDuringCall"] = copy.deepcopy(self.source_call)
                while (len(self.resource_responses) > 4
                       or sum(len(row["body"]) for row in self.resource_responses.values())
                           > 4 * ((2097152 + 2) // 3)):
                    self.resource_responses.pop(next(iter(self.resource_responses)))
                if value["method"] == "GET" and response["status"] == 200:
                    self.resource_documents.add(value["url"])
        if value["method"] == "GET" and response["status"] == 200 and self.events[-1]["responseComplete"]:
            mode = self.policy.get("responseApiOrigins")
            if mode in {"public_https", "public_http_https"}:
                for origin in response_api_origins(
                        base64.b64decode(response["body"], validate=True), mime, value["url"]):
                    if ((origin.startswith("https://") or mode == "public_http_https")
                            and origin not in self.routes and len(self.api_origins) < MAX_ROUTES):
                        self.api_origins.setdefault(origin, (broker, self.events[-1]["responseSha256"]))
            self.register_response_urls(value["url"], response)
            for target in response_query_urls(
                    value["url"], base64.b64decode(response["body"], validate=True),
                    self.policy.get("responseQueries", [])):
                self.register_url(target, parsed.scheme + "://" + parsed.netloc)
            for target, origin in response_json_urls(
                    value["url"], base64.b64decode(response["body"], validate=True),
                    self.policy.get("responseQueries", [])):
                self.register_url(target, origin)
        if value["method"] == "GET":
            self.register_playlist_urls(value["url"], response)
        return response

    def register_redirect(self, url, response, issuer):
        location = response["headers"].get("Location")
        if location is None:
            return
        target = urljoin(url, location)
        parsed = urlsplit(target)
        target = parsed._replace(fragment="").geturl()
        response["headers"]["Location"] = target
        origin = parsed.scheme + "://" + parsed.netloc
        self.last_redirect = {
            "responseStatus": response["status"], "requestUrlSha256": digest(url.encode()),
            "targetUrlSha256": digest(target.encode()), "targetScheme": (
                parsed.scheme if parsed.scheme in {"http", "https"} else "other")}
        try:
            authority, host, port = http_authority(origin, "EGRESS_URL_DENIED")
        except (Denied, ValueError):
            return
        self.last_redirect["targetOrigin"] = authority.scheme + "://" + authority.netloc
        mode = self.policy.get("responseRedirects")
        if mode not in {"public_https", "public_http_https"} or (
                parsed.scheme == "http" and mode != "public_http_https"):
            return
        self.register_public_origin(origin, issuer)

    def register_public_origin(self, origin, issuer):
        if origin not in self.routes:
            require(len(self.routes) < MAX_ROUTES, "EGRESS_REQUEST_BUDGET")
            _, host, port = http_authority(origin, "EGRESS_URL_DENIED")
            try:
                addresses = self.resolve_public_origin(host, port, origin)
            except Denied as error:
                if self.last_dns_observation is None and error.dns_observation is not None:
                    self.record_dns(error.dns_observation, origin)
                raise
            route = {"origin": origin, "exactUrls": [], "requestScope": "origin", **address_pins(addresses),
                     "headerOrigins": list(dict.fromkeys(
                         [issuer.policy["origin"], *issuer.policy.get("headerOrigins", [])]))[:4]}
            if issuer.policy.get("sessionToken") == {"responseScope": "origin"}:
                route["sessionToken"] = {"responseScope": "origin"}
            receiver = Broker(route_policy(self.policy, route), connector=issuer.connector, allow_empty=True)
            receiver.bind_execution(issuer.deadline, issuer.cancelled)
            receiver.source_headers = set(issuer.source_headers)
            if self.source is not None:
                receiver.bind_source(self.source)
            if self.host_credentials:
                receiver.bind_credentials(self.host_credentials)
            self.routes[origin] = receiver

    def register_url(self, target, origin):
        if not isinstance(target, str):
            return
        broker = self.routes[origin]
        configured = broker.policy["exactUrls"]
        if target in configured or len(configured) >= 16:
            return
        try:
            validate_request({"method": "GET", "url": target, "body": "", "headers": {}},
                             {**broker.policy, "exactUrls": [target]})
        except (Denied, ValueError):
            return
        configured.append(target)
        self.events[-1].setdefault("registeredUrlSha256", []).append(digest(target.encode()))

    def register_response_urls(self, url, response):
        rules = [rule for rule in self.policy.get("responseUrls", []) if rule["requestUrl"] == url]
        if not rules:
            return
        try:
            document = json.loads(base64.b64decode(response["body"], validate=True))
        except (ValueError, UnicodeError):
            return
        for rule in rules:
            target = document
            for key in rule["jsonPath"]:
                target = target.get(key) if isinstance(target, dict) else None
            self.register_url(target, rule["targetOrigin"])

    def register_playlist_urls(self, url, response):
        parsed = urlsplit(url)
        origin = parsed.scheme + "://" + parsed.netloc
        event = self.events[-1]
        player_headers = self.player_resources.get(url)
        if (origin not in self.policy.get("hlsOrigins", []) and player_headers is None
                or response["status"] not in {200, 206}
                or not event["responseComplete"]
                or event.get("rangeRequested") and not event.get("resourceComplete")):
            return
        body = base64.b64decode(response["body"], validate=True)
        if not body.lstrip().startswith(b"#EXTM3U"):
            return
        try:
            import m3u8
            map_keys, map_key = [], None
            def track_map_key(line, lineno, data, state):
                nonlocal map_key
                if line.startswith("#EXT-X-MAP:"):
                    map_key = dict(state.get("current_key") or {})
                elif line.startswith("#EXTINF:"):
                    map_keys.append(map_key)
            playlist = m3u8.loads(body.decode("utf-8-sig"), uri=url, custom_tags_parser=track_map_key)
        except (ImportError, ValueError, UnicodeError):
            return
        if playlist.is_variant:
            targets = [item.absolute_uri for item in playlist.playlists[:1]]
        else:
            segments = playlist.segments
            indexes = sorted(set(range(min(3, len(segments)))) | (
                {len(segments) // 2, len(segments) - 1} if segments else set()))
            targets = [segments[index].absolute_uri for index in indexes]
            for index in indexes:
                segment = segments[index]
                if segment.key and segment.key.method not in {None, "NONE"} and segment.key.uri:
                    targets.append(segment.key.absolute_uri)
                if segment.init_section and segment.init_section.uri:
                    targets.append(segment.init_section.absolute_uri)
                    # A MAP keeps its declaration key even after a later METHOD=NONE.
                    key = map_keys[index] if index < len(map_keys) else None
                    if key and key.get("method") not in {None, "NONE"} and key.get("uri"):
                        targets.append(urljoin(url, key["uri"]))
        for target in targets:
            selected = urlsplit(target)
            target_origin = selected.scheme + "://" + selected.netloc
            if player_headers is not None:
                try:
                    self.register_player_resource(target, player_headers)
                except (Denied, ValueError):
                    continue
            elif target_origin in self.policy.get("hlsOrigins", []):
                self.register_url(target, target_origin)


def mediator(policy, **kwargs):
    return Routes(policy, **kwargs) if policy_from(policy)["schemaVersion"] == 3 else Broker(policy, **kwargs)
