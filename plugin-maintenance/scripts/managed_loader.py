"""Host-driven T4 calls. Validate returned contracts, never source-declared verdicts."""
from __future__ import annotations

import base64
import ast
import hashlib
import json
import math
import re
import subprocess
import threading
import time
from html.parser import HTMLParser
from urllib.parse import urlsplit, parse_qsl, urljoin, urldefrag, unquote


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=True, sort_keys=True,
                                      separators=(",", ":")).encode()).hexdigest()


def failure_context(value):
    limits = {"sourceLine": 1000000, "errno": 4095}
    require(isinstance(value, dict) and 1 <= len(value) <= 3
            and set(value) <= set(limits) | {"hostStage"}
            and all(value[key] == "atvp_backend_endpoint" if key == "hostStage" else
                    type(value[key]) is int and 1 <= value[key] <= limits[key] for key in value),
            "LOADER_FAILURE_CONTEXT_INVALID")
    return dict(value)


def init_observation(value):
    """Keep a small, non-content hint when init returned an unusable value."""
    if value is None:
        shape, name_present = "null", False
    elif isinstance(value, dict):
        shape = "object"
        name_present = isinstance(value.get("name"), str) and bool(value["name"].strip())
    elif isinstance(value, list):
        shape, name_present = "array", False
    elif isinstance(value, str):
        text = value.lstrip().lower()
        shape = ("empty_text" if not text else "html" if text.startswith("<") or "<html" in text[:512]
                 else "json_text" if text.startswith(("{", "[")) else "text")
        name_present = False
    elif isinstance(value, bool):
        shape, name_present = "boolean", False
    elif isinstance(value, (int, float)):
        shape, name_present = "number", False
    else:
        shape, name_present = "other", False
    return {"stage": "init_identity", "resultShape": shape, "namePresent": name_present}


def source_purpose_hint(source):
    """Recognize a magnet/cloud-resource source as a compatibility hint only."""
    text = source.lower()
    if (b"magnet:" in text and (b"vod_play_url" in text or b"share" in text)
            and any(marker in text for marker in (b"seed_id", b"pan-links", b"cloud", b"\xe7\xbd\x91\xe7\x9b\x98"))):
        return "magnet_or_cloud_resource"
    return None


STANDARD_METHODS = (
    "init",
    "homeContent",
    "categoryContent",
    "detailContent",
    "searchContent",
    "playerContent",
    "localProxy",
)
SOURCE_IDENTITY_KEYS = ("sourceRuleSha256", "dependencySha256")
MAX_MANIFEST_BYTES = 524288
MAX_RESOURCE_TEXT = 18000
MAX_RESOURCE_FETCHES = 8
MAX_IMAGE_BYTES = 524288
JSON_PRIVATE_FIELD = re.compile(r"token|cookie|authorization|password|passwd|secret|session|credential|api.?key", re.I)
JSON_STATUS_FIELDS = {"code", "status", "success", "ok", "error", "errno", "ret", "retcode"}
JSON_STATUS_VALUES = {"ok", "success", "error", "failed", "failure", "unauthorized", "forbidden",
                      "login_required", "token_expired", "pending"}


def player_value_matches(value, targets):
    if not isinstance(value, str) or not value or len(value) > 8192 or not targets:
        return {}
    variants = {"exact": value}
    if "%" in value:
        variants["percent_decode"] = unquote(value)
    if re.fullmatch(r"[A-Za-z0-9_+/-]+={0,2}", value):
        try:
            variants["base64_decode"] = base64.b64decode(
                value + "=" * (-len(value) % 4), altchars=b"-_", validate=True).decode("utf-8")
        except (ValueError, UnicodeError):
            pass
    return {role: next(kind for kind, decoded in variants.items() if decoded == target)
            for role, target in targets.items() if isinstance(target, str) and target
            and any(decoded == target for decoded in variants.values())}


def json_structure(body, *, embedded=True, player_values=None):
    """Describe the observed wrapper without copying payloads, credentials or message text."""
    try:
        value = json.loads(body)
    except (ValueError, UnicodeError, RecursionError):
        return None
    result = {"nodes": [], "truncated": False}
    embedded_count = 0

    def visit(value, path):
        nonlocal embedded_count
        if len(result["nodes"]) >= 48:
            result["truncated"] = True
            return
        field = path[-1] if path and isinstance(path[-1], str) else ""
        kind = ("redacted" if JSON_PRIVATE_FIELD.search(field) else
                "null" if value is None else "boolean" if isinstance(value, bool) else
                "object" if isinstance(value, dict) else "array" if isinstance(value, list) else
                "string" if isinstance(value, str) else "integer" if isinstance(value, int) else "number")
        node = {"path": path, "type": kind}
        result["nodes"].append(node)
        if kind == "redacted":
            return
        if kind in {"object", "array"}:
            node["size"] = len(value)
            if len(path) >= 6:
                result["truncated"] |= bool(value)
                return
            items = value.items() if kind == "object" else enumerate(value[:3])
            if kind == "array" and len(value) > 3:
                result["truncated"] = True
            for key, item in items:
                if len(result["nodes"]) >= 48:
                    result["truncated"] = True
                    break
                if kind == "object" and not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]{0,47}", key):
                    result["truncated"] = True
                    continue
                visit(item, path + [key])
        elif kind == "string":
            node["byteLength"] = len(value.encode("utf-8", errors="replace"))
            node["shape"] = ("empty" if not value else "url_like" if value.startswith(("http://", "https://", "magnet:")) else
                             "json_like" if value.lstrip().startswith(("{", "[")) else
                             "hex_like" if len(value) >= 16 and re.fullmatch(r"[a-fA-F0-9]+", value) else
                             "base64_like" if len(value) >= 16 and re.fullmatch(r"[A-Za-z0-9_+/-]+={0,2}", value) else "text")
            matches = player_value_matches(value, player_values)
            if matches:
                node["playerValueMatches"] = matches
            if embedded and embedded_count < 3 and node["shape"] == "json_like" and node["byteLength"] <= 8192:
                decoded = json_structure(value, embedded=False, player_values=player_values)
                if decoded is not None:
                    node["embeddedJson"] = decoded
                    embedded_count += 1
        if field.lower() in JSON_STATUS_FIELDS:
            if kind == "boolean" or kind == "integer" and -999999 <= value <= 999999:
                node["safeValue"] = value
            elif kind == "string" and value.lower() in JSON_STATUS_VALUES:
                node["safeValue"] = value.lower()

    visit(value, [])
    while len(json.dumps(result, separators=(",", ":")).encode()) > 2048:
        result["nodes"].pop()
        result["truncated"] = True
    return result


class ResourceLinks(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "script" and attrs.get("src"):
            self.links.append(attrs["src"])
        elif tag == "link" and attrs.get("href") and (
                attrs.get("rel") == "modulepreload"
                or attrs.get("rel") == "preload" and attrs.get("as") == "script"):
            self.links.append(attrs["href"])


def resource_links(body, page=False):
    text = body.decode("utf-8-sig", errors="replace")
    if page:
        parser = ResourceLinks()
        parser.feed(text)
        return parser.links[:32]
    # Static module specifiers and bundler asset tables; never execute the JavaScript.
    return re.findall(r"""["'`]((?:\.{0,2}/)?[A-Za-z0-9_./@-]+\.m?js(?:\?[A-Za-z0-9_=.&%-]*)?)["'`]""",
                      text)[:128]


def resource_excerpts(body, page=False, *, playback=False, content=False):
    if len(body) <= 6000 and not (playback and len(body) > 2000):
        return [(1, 0, body)]
    if page:
        # Spread structural samples across the response, not only its navigation/header.
        ranges = [] if playback else [(2, 0, min(2000, len(body)))]
        patterns = (
                (6, rb"__NEXT_DATA__|__NUXT__|_ROUTER_DATA|application/ld\+json"),
                (5, rb"""<(?:article|main|section)\b|(?:class|id)\s*=\s*["'][^"']{0,100}(?:list|item|card|vod|video|module)"""),
                (4, rb""""(?:list|items|data|results|episodes|videos)"\s*:|<script\b"""))
        if playback:
            patterns = (
                (10, rb"""\b(?:var|let|const)\s+[\w$]*(?:player|video|media|play|config)[\w$]*\s*=|["'](?:url|file|sources|encrypt)["']\s*:|\bplayer_[\w$]+\s*="""),
                (9, rb"<(?:video|source|iframe)\b|\.m3u8|\.mp4"), *patterns)
        if content:
            patterns = ((12, rb"""<(?:div|ul|ol|section)\b[^>]*(?:id|class)\s*=\s*["'][^"']{0,160}(?:playlist|play[-_]list|episode|anthology)"""), *patterns)
        for score, pattern in patterns:
            matches = list(re.finditer(pattern, body, re.IGNORECASE))[:512]
            selected = sorted({round(i * (len(matches) - 1) / 7) for i in range(8)}) if matches else []
            for index in selected:
                start = max(0, matches[index].start() - 400)
                end = min(len(body), matches[index].end() + 1200)
                if not any(start < previous_end and end > previous_start
                           for _, previous_start, previous_end in ranges):
                    ranges.append((score, start, end))
        ranges.extend([(1, 0, min(6000, len(body))), (1, max(0, len(body) - 2000), len(body))])
        return [(score, start, body[start:end]) for score, start, end in ranges]
    markers = (
        (8, rb"decrypt|createObjectURL|createImageBitmap"),
        (5, rb"arrayBuffer|CryptoJS|WordArray|AES[.(]"),
        (2, rb"Uint8Array|atob[(]|image/"),
    )
    if playback:
        markers = (
            (10, rb"""\bplayer_[\w$]+\s*=|["'](?:file|sources)["']\s*:|\b(?:player|video)\s*\.\s*(?:setup|load)\s*\("""),
            (8, rb"\.m3u8|\.mp4|video[.(]|player[.(]"), *markers)
    found = []
    for score, pattern in markers:
        matches = re.finditer(pattern, body, re.IGNORECASE)
        for index, match in enumerate(matches):
            if index == 16:
                break
            start, end = max(0, match.start() - (400 if playback else 1200)), min(len(body), match.end() + 1800)
            if not any(start < previous_end and end > previous_start
                       for _, previous_start, previous_end in found):
                found.append((score, start, end))
    return ([(score, start, body[start:end]) for score, start, end in found[:48]]
            or ([(1, 0, body[:6000])] if playback else []))


class IndexedPlaylists(HTMLParser):
    """Observe complete, explicitly indexed HTML playlists without executing page code."""
    VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}

    def __init__(self, base):
        super().__init__(convert_charrefs=True)
        self.base, self.stack, self.groups, self.invalid = base, [], [], False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        active = next((group for _, group in reversed(self.stack) if group is not None), None)
        group = None
        if re.fullmatch(r"playlist\d{1,4}", attrs.get("id", ""), re.I):
            if active is not None or len(self.groups) >= 128:
                self.invalid = True
            else:
                group = {"id": attrs["id"].lower(), "links": [], "closed": False}
                self.groups.append(group)
        if tag == "a" and active is not None:
            href = attrs.get("href", "").strip()
            route = urlsplit(urljoin(self.base, href))
            if not href or route.scheme not in {"http", "https"} or not route.hostname or len(active["links"]) >= 10000:
                self.invalid = True
            else:
                active["links"].append(urljoin(self.base, href))
        if tag not in self.VOID:
            self.stack.append((tag, group))

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        index = next((i for i in range(len(self.stack) - 1, -1, -1) if self.stack[i][0] == tag), None)
        if index is None:
            return
        for i, (_, group) in enumerate(self.stack[index:], index):
            if group is not None:
                group["closed"] = i == index
                if not group["closed"]:
                    self.invalid = True
        del self.stack[index:]


def playlist_observation(body, url, output):
    parser = IndexedPlaylists(url)
    try:
        parser.feed(body.decode("utf-8-sig"))
        parser.close()
        groups = parser.groups
        if not groups:
            return None
        links = sorted(sorted(group["links"]) for group in groups)
        complete = (not parser.invalid and all(group["closed"] and group["links"] for group in groups)
                    and len({group["id"] for group in groups}) == len(groups))
        matches, coverage = False, None
        try:
            _, details = cards(output)
            if len(details) == 1:
                extracted, comparable = [], True
                for group in details[0].get("vod_play_url", "").split("$$$"):
                    values = []
                    for episode in group.split("#"):
                        name, separator, value = episode.partition("$")
                        require(name.strip() and separator and value, "PLAY_EPISODE_INVALID")
                        comparable = comparable and (value.startswith(("http://", "https://", "/", "./", "../")))
                        values.append(urljoin(url, value))
                    extracted.append(sorted(values))
                matches = complete and sorted(extracted) == links
                if complete and comparable:
                    page_links = {link for group in links for link in group}
                    output_links = {link for group in extracted for link in group}
                    coverage = {"pageUniqueLinks": len(page_links), "outputUniqueLinks": len(output_links),
                                "missingPageLinks": len(page_links - output_links),
                                "extraOutputLinks": len(output_links - page_links)}
        except (Invalid, ValueError, TypeError, KeyError, AttributeError):
            pass
        counts = [len(group["links"]) for group in groups]
        result = {"policy": "indexed_playlist_links_v1", "complete": complete, "groups": len(groups),
                "maxEpisodes": max(counts), "groupEpisodeCounts": counts,
                "playlistSha256": digest(links), "outputMatchesPage": matches}
        if coverage is not None:
            result["linkCoverage"] = coverage
        return result
    except (ValueError, UnicodeError):
        return None


def collect_resource_evidence(broker, source_sha, image=None, *, target_node="C09", player_page=None,
                              player_values=None, cached_calls=None, text_budget=MAX_RESOURCE_TEXT, detail_output=None):
    value = {"schemaVersion": 1, "authority": "host_public_resource_observed",
             "artifactSha256": source_sha, "targetNode": target_node, "image": image,
             "resources": [], "failures": [], "textByteLimit": MAX_RESOURCE_TEXT,
             "fetchLimit": MAX_RESOURCE_FETCHES, "sourceCodeExecuted": False,
             "businessVerdictTrusted": False}
    if player_values is not None:
        value["playerContext"] = {key: player_values[key] for key in ("detailCallId", "playCallId")}
    entries = getattr(broker, "policy", {}).get("resourceEvidence", {}).get("entryUrls", [])
    fetch = getattr(broker, "fetch_resource", None)
    snapshot = getattr(broker, "snapshot_resources", lambda: [])()
    if cached_calls is not None:
        # Capture the completed action before the bounded broker cache evicts its response.
        snapshot = [(url, response) for url, response in snapshot
                    if response.get("observedDuringCall", {}).get("id") in cached_calls]
        snapshot.sort(key=lambda item: (item[1]["status"] != 200, not item[1].get("body")))
        entries, fetch = [], None
    if not snapshot and (not fetch or not entries) and player_page is None:
        value["collectionStatus"] = "not_configured"
        return value
    cached = dict(snapshot)
    preferred = [player_page["url"]] if player_page is not None else []
    # The broker already returns newest first. Keep the play action ahead of older pages.
    observed = sorted(snapshot, key=lambda item: (
        item[1].get("observedDuringCall", {}).get("method") != "play",
        item[1]["status"] != 200)) if target_node == "R01" else snapshot
    queue = [(url, None, 0) for url in dict.fromkeys(preferred + [url for url, _ in observed] + entries)]
    visited, excerpts, discovery_calls = set(), [], {}
    structure_bytes = 0
    while queue and len(visited) < (1 if cached_calls is not None else MAX_RESOURCE_FETCHES):
        url, parent, depth = queue.pop(0)
        if url in visited:
            continue
        visited.add(url)
        try:
            if url in cached:
                response = cached[url]
            elif player_page is not None and url == player_page["url"]:
                # Use the existing source route and budget, not a new parser or an origin grant.
                response = broker.fetch({"method": "GET", "url": url, "body": "",
                                         "headers": player_page["headers"]})
            else:
                response = fetch(url, parent)
            body = base64.b64decode(response["body"], validate=True)
            method = response.get("requestMethod", "GET")
            mime = next((v.split(";", 1)[0].strip().lower() for k, v in response.get("headers", {}).items()
                         if k.lower() == "content-type"), "")
            is_json = mime == "application/json" or mime.endswith("+json") or body.lstrip().startswith((b"{", b"["))
            call = response.get("observedDuringCall", {})
            matched_call = player_values is not None and any(
                call.get("id") == player_values[key] and call.get("method") == method
                for key, method in (("detailCallId", "detail"), ("playCallId", "play")))
            targets = ({key: player_values[key] for key in ("selectedIdentifier", "returnedUrl")}
                       if matched_call else None)
            structure = json_structure(body, player_values=targets) if is_json else None
            require(not (is_json or method == "POST") or structure is not None, "RESOURCE_JSON_UNAVAILABLE")
            require(response["status"] != 200 or parent is None
                    or not body.lstrip().lower().startswith((b"<!doctype", b"<html")),
                    "RESOURCE_SCRIPT_RETURNED_HTML")
            row = {"kind": "api_json" if structure is not None else "page" if parent is None else "script",
                   "urlSha256": hashlib.sha256(url.encode()).hexdigest(),
                   "parentUrlSha256": hashlib.sha256(parent.encode()).hexdigest() if parent else None,
                   "responseSha256": hashlib.sha256(body).hexdigest(), "responseBytes": len(body),
                   "responseStatus": response["status"],
                   "excerpts": []}
            if structure is not None:
                row["requestMethod"] = method
                row["jsonStructure"] = structure
                structure_bytes += len(json.dumps(structure, separators=(",", ":")).encode())
            elif cached_calls is not None and response["status"] == 200:
                observation = playlist_observation(body, url, detail_output)
                if observation is not None:
                    row["playlistObservation"] = observation
                    structure_bytes += len(json.dumps(observation, separators=(",", ":")).encode())
            if "observedDuringCall" in response:
                row["observedDuringCall"] = response["observedDuringCall"]
            if structure is None and parent in discovery_calls:
                row["discoveredFromCall"] = discovery_calls[parent]
            call = row.get("observedDuringCall", row.get("discoveredFromCall"))
            if call is not None:
                discovery_calls[url] = call
            value["resources"].append(row)
            if structure is not None:
                continue
            excerpts.extend((score, len(value["resources"]) - 1, offset, chunk)
                            for score, offset, chunk in resource_excerpts(
                                body, parent is None or response["status"] != 200,
                                playback=target_node == "R01" and response["status"] == 200,
                                content=cached_calls is not None))
            if fetch and depth < 2 and response["status"] == 200:
                links = resource_links(body, parent is None)
                if target_node == "R01":
                    links.sort(key=lambda link: (
                        not bool(re.search(r"player|play|video|media|config", link, re.I)),
                        not bool(re.search(r"crypto|decrypt", link, re.I))))
                else:
                    links.sort(key=lambda link: not re.search(r"image|img|crypto|decrypt", link, re.I))
                discovered = []
                for link in links:
                    # Bundler asset tables use document-relative paths, unlike ./module imports.
                    base = (url if link.startswith((".", "/")) else next(
                        (entry for entry in entries if urlsplit(entry).netloc == urlsplit(url).netloc), url))
                    target = urldefrag(urljoin(base, link))[0]
                    parsed, current = urlsplit(target), urlsplit(url)
                    if ((parsed.scheme, parsed.netloc) == (current.scheme, current.netloc)
                            and parsed.path.lower().endswith((".js", ".mjs"))
                            and target not in visited
                            and all(item[0] != target for item in queue + discovered)):
                        discovered.append((target, url, depth + 1))
                if target_node == "R01":
                    # Breadth-first within this page's scripts before unrelated cached pages.
                    index = next((i for i, item in enumerate(queue) if item[1] is None), len(queue))
                    queue[index:index] = discovered
                else:
                    queue.extend(discovered)
        except Exception as error:
            code = getattr(error, "code", None)
            if isinstance(error, Invalid) and str(error) in {"RESOURCE_SCRIPT_RETURNED_HTML", "RESOURCE_JSON_UNAVAILABLE"}:
                code = str(error)
            value["failures"].append({"urlSha256": hashlib.sha256(url.encode()).hexdigest(),
                                      "code": code if isinstance(code, str) and re.fullmatch(
                                          r"EGRESS_[A-Z_]{1,64}|RESOURCE_SCRIPT_RETURNED_HTML|RESOURCE_JSON_UNAVAILABLE", code)
                                      else "RESOURCE_FETCH_UNAVAILABLE"})
    text_limit = min(MAX_RESOURCE_TEXT, text_budget)
    remaining = text_limit - structure_bytes
    ordered = sorted(excerpts, key=lambda item: -item[0])
    retained = {}

    def append_excerpt(item, limit):
        nonlocal remaining
        _, index, offset, chunk = item
        raw = chunk[:min(remaining, limit)]
        if not raw:
            return
        text = raw.decode("utf-8", errors="replace")
        # Excerpts are a UTF-8 projection, not a claim to reconstruct the original response.
        encoded = text.encode()
        while len(encoded) > min(remaining, limit):
            text = text[:-1]
            encoded = text.encode()
        if not text:
            return
        value["resources"][index]["excerpts"].append({
            "offset": offset, "text": text, "textSha256": hashlib.sha256(encoded).hexdigest()})
        remaining -= len(encoded)
        retained.setdefault(index, []).append((offset, offset + len(raw)))

    error_rows = {index for index, row in enumerate(value["resources"]) if row["responseStatus"] >= 400}
    if target_node == "R01":
        best_rows = {}
        for item in ordered:
            best_rows.setdefault(item[1], item)
        for index in sorted(error_rows.intersection(best_rows)):
            append_excerpt(best_rows[index], min(6000, MAX_RESOURCE_TEXT // max(1, len(error_rows))))
        other_rows = [index for index in best_rows if index not in error_rows]
        limit = min(6000, remaining // max(1, 2 * len(other_rows)))
        for index in sorted(other_rows):
            append_excerpt(best_rows[index], limit)
    elif error_rows:
        # Keep each observed page's strongest context before libraries compete for the remainder.
        best_pages = {}
        for item in ordered:
            if item[1] in error_rows or not error_rows and value["resources"][item[1]]["kind"] == "page":
                best_pages.setdefault(item[1], item)
        page_limit = min(6000, MAX_RESOURCE_TEXT // max(1, (1 if error_rows else 2) * len(best_pages)))
        for index in sorted(best_pages):
            append_excerpt(best_pages[index], page_limit)
    for item in ordered:
        if remaining <= 0:
            break
        _, index, offset, chunk = item
        if (target_node == "R01" or error_rows) and any(
                offset < end and offset + len(chunk) > start for start, end in retained.get(index, [])):
            continue
        append_excerpt(item, remaining)
    value["textBytes"] = text_limit - remaining
    value["collectionStatus"] = "observed" if value["resources"] else "unavailable"
    return value


def source_identity(source):
    """Derive the same bounded AST identities emitted by the AT projection."""
    try:
        tree = ast.parse(source.decode("utf-8-sig"))
    except (UnicodeDecodeError, SyntaxError):
        raise Invalid("LOADER_SOURCE_SYNTAX_INVALID") from None
    methods = sorted({
        node.name
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    })
    imports = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imports.update(alias.name.split(".", 1)[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imports.add(node.module.split(".", 1)[0])
    rules = {
        "standardMethods": [name for name in STANDARD_METHODS if name in methods],
        "methodCount": str(len(methods)),
        "hasProxyBase": str(b"proxy_base" in source.lower()).lower(),
        "hasCookie": str(b"cookie" in source.lower()).lower(),
        "hasSession": str(b"session" in source.lower()).lower(),
        "hasLocalProxy": str("localProxy" in methods).lower(),
        "httpCallMarkers": {
            "get": str(source.lower().count(b".get(")),
            "post": str(source.lower().count(b".post(")),
            "session": str(source.lower().count(b"requests.session")),
        },
    }
    return {
        "sourceRuleSha256": digest(rules),
        "dependencySha256": digest({"imports": sorted(imports)}),
    }


def validate_source_identity(source, data):
    """Validate optional projection identities against the exact source bytes."""
    present = [key for key in SOURCE_IDENTITY_KEYS if key in data]
    require(not present or len(present) == len(SOURCE_IDENTITY_KEYS),
            "T4_RPC_SOURCE_DATA_INVALID")
    if not present:
        return {}
    identity = source_identity(source)
    require(all(isinstance(data[key], str) and re.fullmatch(r"[a-f0-9]{64}", data[key])
                and data[key] == identity[key] for key in SOURCE_IDENTITY_KEYS),
            "T4_RPC_SOURCE_DATA_INVALID")
    return identity


class Invalid(Exception):
    pass


def require(condition, reason):
    if not condition:
        raise Invalid(reason)


def document(value):
    if isinstance(value, str):
        value = json.loads(value)
    require(isinstance(value, dict), "RETURN_OBJECT_REQUIRED")
    return value


def cards(value, *, allow_empty=False, allow_duplicates=False):
    value = document(value)
    rows = value.get("list")
    require(isinstance(rows, list) and (allow_empty or bool(rows)), "CONTENT_LIST_EMPTY_OR_INVALID")
    require(all(isinstance(row, dict) and isinstance(row.get("vod_id"), str) and row["vod_id"]
                and isinstance(row.get("vod_name"), str) and row["vod_name"].strip()
                for row in rows), "CONTENT_CARD_INVALID")
    require(allow_duplicates or len({row["vod_id"] for row in rows}) == len(rows), "CONTENT_IDS_DUPLICATED")
    return value, rows


def detail_identity(selected, detail):
    selected_id, returned_id = selected["vod_id"], detail["vod_id"]
    guid, separator, cover = selected_id.partition("###")
    return {
        "selectedIdSha256": hashlib.sha256(selected_id.encode()).hexdigest(),
        "returnedIdSha256": hashlib.sha256(returned_id.encode()).hexdigest(),
        "exactMatch": selected_id == returned_id,
        "guidCoverMatch": bool(separator and cover == selected.get("vod_pic") == detail.get("vod_pic")
                               and returned_id == guid),
        "atvpDetailPrefixMatch": (selected.get("vod_tag") == "folder"
                                 and selected_id.startswith("atvp_detail:")
                                 and selected_id[len("atvp_detail:"):] == returned_id),
        "nameMatch": selected["vod_name"] == detail["vod_name"],
        "coverMatch": bool(selected.get("vod_pic")) and selected.get("vod_pic") == detail.get("vod_pic"),
    }


def player_url_shape(value):
    if not isinstance(value, str):
        return "not_string"
    if not value.strip():
        return "empty"
    text = value.strip()
    try:
        parsed = urlsplit(text)
        if parsed.scheme in {"http", "https"}:
            return "absolute_http" if parsed.hostname else "malformed"
        if parsed.scheme == "magnet":
            return "magnet"
        if text.startswith("//"):
            return "protocol_relative"
        if text.startswith(("/", "./", "../")):
            return "relative_path"
        if text.startswith(("{", "[", '"')):
            return "json_like"
        if text.startswith("<"):
            return "html_like"
        # Recognition only: never replace the URL, execute decoded text or request it.
        if len(text) <= 8192:
            if unquote(text).lower().startswith(("http://", "https://", "//")):
                return "percent_encoded_url"
            if re.fullmatch(r"[A-Za-z0-9_+/-]+={0,2}", text):
                try:
                    decoded = base64.b64decode(text + "=" * (-len(text) % 4), altchars=b"-_", validate=True)
                    if decoded.lower().startswith((b"http://", b"https://", b"//")):
                        return "base64_encoded_url"
                except ValueError:
                    pass
        return "custom_scheme" if parsed.scheme else "opaque"
    except ValueError:
        return "malformed"


def player_output_contract(player, selected_identifier, detail_call_id, play_call_id):
    url = player.get("url")
    url_type = ("missing" if "url" not in player else "null" if url is None else
                "string" if isinstance(url, str) else "boolean" if isinstance(url, bool) else
                "number" if isinstance(url, (int, float)) else "object" if isinstance(url, dict) else
                "array" if isinstance(url, list) else "other")
    direct = (all(type(player.get(key, 0)) in {int, str} and str(player.get(key, 0)) == "0"
                  for key in ("parse", "jx")) and player.get("playUrl", "") == "")
    value = {
        "detailCallId": detail_call_id, "playCallId": play_call_id,
        "selectedIdentifierShape": player_url_shape(selected_identifier),
        "returnedUrlShape": player_url_shape(url), "returnedUrlType": url_type,
        "returnedUrlBytes": len(url.encode()) if isinstance(url, str) else 0,
        "returnedUrlMatchesIdentifier": isinstance(url, str) and url == selected_identifier,
        "parseMode": "direct" if direct else "parse_required",
        "hasWhitespace": isinstance(url, str) and any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in url),
        "hasFragment": False, "hasUserInfo": False, "hasHostname": False,
    }
    if isinstance(url, str):
        try:
            parsed = urlsplit(url)
            value.update(hasFragment=bool(parsed.fragment), hasUserInfo=parsed.username is not None,
                         hasHostname=bool(parsed.hostname))
        except ValueError:
            pass
    return value


def player_contract(player):
    url = player.get("url")
    value = {"playerRouteKind": "absent", "offlineResolverRequired": False,
             "magnetInfoHashValid": False}
    if not isinstance(url, str) or not url:
        return value
    value["playerUrlSha256"] = hashlib.sha256(url.encode()).hexdigest()
    value["playerRouteKind"] = "other"
    try:
        parsed = urlsplit(url)
        if parsed.scheme in {"http", "https", "magnet"}:
            value["playerRouteKind"] = parsed.scheme
        if parsed.scheme == "magnet":
            value["magnetInfoHashValid"] = (
                not parsed.netloc and not parsed.path and not parsed.fragment
                and not re.search(r"[\x00-\x20\x7f]", url) and any(
                key.lower() == "xt" and re.fullmatch(
                    r"urn:btih:(?:[a-f0-9]{40}|[a-z2-7]{32})", item, re.I) is not None
                for key, item in parse_qsl(parsed.query)))
            value["offlineResolverRequired"] = value["magnetInfoHashValid"]
    except ValueError:
        pass
    return value


def decode_image(body):
    common = ["-v", "error", "-protocol_whitelist", "pipe", "-i", "pipe:0"]
    probe = subprocess.run(
        ["/usr/bin/ffprobe", *common, "-show_entries", "stream=width,height", "-of", "json"],
        input=body, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=True, env={})
    streams = json.loads(probe.stdout)["streams"]
    require(len(streams) == 1 and all(type(streams[0].get(key)) is int for key in ("width", "height"))
            and streams[0]["width"] > 0 and streams[0]["height"] > 0
            and streams[0]["width"] * streams[0]["height"] <= 40_000_000, "COVER_DIMENSIONS_INVALID")
    subprocess.run(
        ["/usr/bin/ffmpeg", *common, "-threads", "1", "-frames:v", "1", "-f", "null", "-"],
        input=body, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=5, check=True, env={})
    return {"width": streams[0]["width"], "height": streams[0]["height"]}


class Loader:
    SCOPE = "t4-host-loader-observation-v1"
    OBSERVED = ("S06", "C01", "C03", "C07", "C09", "C10", "R01", "M01")
    UNAVAILABLE = {
        "S07": "TOP_LEVEL_JSONL_ENTRYPOINT_NOT_IN_SAMPLE_RUNNER",
        "S08": "FRESH_WORKER_CACHE_COMPARISON_NOT_IN_SAMPLE_RUNNER",
    }
    FAILURE_TYPES = frozenset({
        "ModuleNotFoundError", "ImportError", "NameError", "AttributeError", "TypeError",
        "ValueError", "KeyError", "IndexError", "ZeroDivisionError", "RuntimeError",
        "NotImplementedError", "RecursionError", "UnicodeDecodeError", "JSONDecodeError",
        "TimeoutError", "ConnectionError", "OSError", "SyntaxError",
    })

    @staticmethod
    def validate_profile(profile):
        require(isinstance(profile, dict) and type(profile.get("schemaVersion")) is int
                    and profile["schemaVersion"] == 1
                    and isinstance(profile.get("requiredNodes"), list)
                    and bool(profile["requiredNodes"])
                    and all(isinstance(node, str) and node in {
                        *("S%02d" % i for i in range(1, 9)), *("C%02d" % i for i in range(1, 12)),
                        *("R%02d" % i for i in range(1, 11)), *("M%02d" % i for i in range(1, 11)),
                        *("P%02d" % i for i in range(1, 6))} for node in profile["requiredNodes"])
                    and len(set(profile["requiredNodes"])) == len(profile["requiredNodes"]),
                    "LOADER_PROFILE_INVALID")
        require(profile.get("detailIdentity", "source-navigation") in {
                    "exact", "cctv-guid-cover", "exact-or-guid-cover", "source-navigation"},
                "LOADER_PROFILE_INVALID")
        require(isinstance(profile.get("paginationMode", "exact-total"), str)
                and profile.get("paginationMode", "exact-total") in {"exact-total", "page-window"},
                "LOADER_PROFILE_INVALID")
        purpose = profile.get("sourcePurpose", "video")
        require(isinstance(purpose, str) and purpose in {"video", "metadata-navigation", "magnet"},
                "LOADER_PROFILE_INVALID")
        if purpose in {"metadata-navigation", "magnet"}:
            targets = set(profile["requiredNodes"]) | set(profile.get("optionalNodes", []))
            if purpose == "metadata-navigation":
                require("C07" in profile["requiredNodes"] and {"C03", "C05"}.intersection(profile["requiredNodes"])
                        and not any(re.fullmatch(r"C1[01]|R0[1-8]|M(?:0[1-9]|10)|P0[1-5]", node)
                                    for node in targets), "LOADER_PROFILE_INVALID")
            else:
                require({"C03", "C05", "C07", "C10", "R01"}.intersection(targets)
                        and not any(re.fullmatch(r"M(?:0[1-9]|10)|P0[1-5]", node) for node in targets),
                        "LOADER_PROFILE_INVALID")
        if "searchKeyword" in profile:
            keyword = profile["searchKeyword"]
            require(isinstance(keyword, str) and 0 < len(keyword) <= 200 and keyword.strip()
                    and all(ord(ch) >= 32 and not 127 <= ord(ch) <= 159 for ch in keyword),
                    "LOADER_PROFILE_INVALID")
        if "publicSourceData" in profile:
            T4Call.validate_source_data(profile["publicSourceData"])
        if "sourceConfig" in profile:
            T4Call.validate_source_data(profile["sourceConfig"], allow_empty=True)
        if "httpPlaybackGroup" in profile:
            require(type(profile["httpPlaybackGroup"]) is int
                    and 0 <= profile["httpPlaybackGroup"] <= 31, "LOADER_PROFILE_INVALID")
        if "httpDetailSample" in profile:
            sample = profile["httpDetailSample"]
            require(isinstance(sample, str) and 0 < len(sample) <= 512
                    and all(ord(ch) >= 32 and not 127 <= ord(ch) <= 159
                            and not 0xD800 <= ord(ch) <= 0xDFFF for ch in sample),
                    "LOADER_PROFILE_INVALID")

    def __init__(self, source, runtime_sha, broker, profile=None, mode="full"):
        if profile is not None:
            self.validate_profile(profile)
        self.profile = json.loads(json.dumps(profile)) if profile is not None else None
        self.required = tuple(dict.fromkeys(("S06", *(profile["requiredNodes"] if profile else self.OBSERVED))))
        self.targets = set(self.required) | set(profile.get("optionalNodes", []) if profile else ())
        self.source_sha = hashlib.sha256(source).hexdigest()
        # Atvp reads the local file in text mode, which normalizes Windows/Mac newlines.
        loaded_text = source.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
        self.text_sha = hashlib.sha256(loaded_text.encode()).hexdigest()
        self.source_identity = source_identity(source)
        if self.profile and "publicSourceData" in self.profile:
            validate_source_identity(source, self.profile["publicSourceData"])
        self.source_data = json.loads(json.dumps(
            self.profile.get("sourceConfig", {}) if self.profile is not None else {}))
        if self.profile is not None and "sourceConfig" in self.profile:
            T4Call.validate_source_data(self.source_data, allow_empty=True)
        self.runtime_sha, self.broker = runtime_sha, broker
        self.source = source
        require(mode in {"full", "cold"}, "LOADER_MODE_INVALID")
        self.mode = mode
        self.nodes, self.calls = [], []
        self.loaded = False
        self.jsonl_observed = False
        self.hot_home = None
        self.awaiting = None
        self.response = None
        self.resource_evidence = None
        self.content_resource_evidence = None
        self.content_pages, self.content_details = {}, {}
        self.content_snapshot = None
        self.content_sampled = False
        self.summary = {"status": "source_exception", **{key: 0 for key in (
            "homeClasses", "homeItems", "homeCovers", "categoryItems", "categoryCovers",
            "page2Items", "detailItems", "detailCovers", "playGroups", "playEpisodes")},
            "playerStatus": "not_sampled", "playerUrlKind": "absent",
            "playerParse": "invalid", "mediaStatus": "not_requested"}
        self.iterator = self.run()

    def source_ready(self, frame):
        require(self.awaiting is not None and self.awaiting["method"] == "init" and not self.loaded,
                "LOADER_SOURCE_ORDER_INVALID")
        require(frame == {"kind": "loader_source", "sourceTextSha256": self.text_sha},
                "LOADER_SOURCE_IDENTITY_MISMATCH")
        self.loaded = True
        return {"kind": "loader_ack"}

    def next(self):
        try:
            method, params = next(self.iterator)
        except StopIteration:
            self.awaiting = None
            getattr(self.broker, "set_source_call", lambda call: None)(None)
            return {"kind": "finish"}
        require(self.awaiting is None, "LOADER_CALL_ORDER_INVALID")
        self.awaiting = {"kind": "call", "id": len(self.calls) + 1, "method": method, "params": params}
        getattr(self.broker, "set_source_call", lambda call: None)(
            {"id": self.awaiting["id"], "method": method, "inputSha256": digest(params)})
        self.started = time.monotonic()
        return self.awaiting

    def accept(self, frame):
        call = self.awaiting
        require(call is not None and isinstance(frame, dict)
                and type(frame.get("id")) is int and frame["id"] == call["id"]
                and type(frame.get("ok")) is bool and frame.get("kind") == "call_result"
                and set(frame) == ({"kind", "id", "ok", "result"} if frame["ok"] else
                                   {"kind", "id", "ok", "code"} | ({"failureContext"} if "failureContext" in frame else set())),
                "LOADER_CALL_RESULT_MISMATCH")
        self.calls.append({"id": call["id"], "method": call["method"], "ok": frame["ok"],
                           "inputSha256": digest(call["params"]),
                           "outputSha256": digest(frame.get("result") if frame["ok"] else None),
                           "elapsedMillis": round((time.monotonic() - self.started) * 1000)})
        if not frame["ok"] and frame["code"] in self.FAILURE_TYPES:
            self.calls[-1]["failureType"] = frame["code"]
            if "failureContext" in frame:
                self.calls[-1]["failureContext"] = failure_context(frame["failureContext"])
        self.response = frame
        self.awaiting = None
        getattr(self.broker, "set_source_call", lambda call: None)(None)
        return self.next()

    def invoke(self, method, params):
        yield method, params
        if method == "detail":
            captured = collect_resource_evidence(self.broker, self.source_sha, target_node="C10",
                                                cached_calls={len(self.calls)}, text_budget=2048,
                                                detail_output=self.response.get("result"))
            if captured["resources"]:
                if self.content_resource_evidence is None:
                    self.content_resource_evidence = captured
                else:
                    existing = self.content_resource_evidence
                    seen = {row["urlSha256"] for row in existing["resources"]}
                    for row in captured["resources"]:
                        if row["urlSha256"] not in seen and len(existing["resources"]) < MAX_RESOURCE_FETCHES:
                            existing["resources"].append(row)
                            existing["textBytes"] += captured["textBytes"]
        require(self.response["ok"], "LOADER_METHOD_FAILED")
        result = self.response["result"]
        if method == "category" and params.get("pg") == "1":
            self.content_pages[params["t"]] = (result, len(self.calls))
        elif method == "detail" and params.get("ids"):
            self.content_details[params["ids"]] = (result, len(self.calls))
        return result

    def sample_content(self, home):
        """Observe every small source's category and one detail per category, without playing it."""
        classes = home.get("class", []) if home else []
        if not isinstance(classes, list) or not classes or not all(
                isinstance(row, dict) and isinstance(row.get("type_id"), str) and row["type_id"]
                for row in classes) or len({row["type_id"] for row in classes}) != len(classes):
            return
        declared = sorted(digest(row["type_id"]) for row in classes)
        details_sampled = bool(self.targets.intersection({"C07", "C08", "C09", "C10", "C11", "R01", "R06",
                                                        *("M%02d" % i for i in range(1, 11))}))
        # Keep large navigation catalogues bounded; disclose exactly which categories were sampled.
        self.content_snapshot = {"schemaVersion": 1, "declaredCategories": len(classes),
                                 "declaredCategoryIdsSha256": digest(declared),
                                 "allCategoriesSampled": len(classes) <= 12,
                                 "detailsSampled": details_sampled, "categories": []}
        for category in sorted(classes, key=lambda row: digest(row["type_id"]))[:12]:
            ident = category["type_id"]
            row = {"categoryIdSha256": digest(ident), "status": "error", "firstItems": 0,
                   "categoryCallId": 0}
            self.content_snapshot["categories"].append(row)
            try:
                if ident not in self.content_pages:
                    yield from self.invoke("category", {"t": ident, "pg": "1", "filter": True, "extend": {}})
                raw, call_id = self.content_pages[ident]
                row["categoryCallId"] = call_id
                _, items = cards(raw, allow_empty=True)
                row.update(status="returned" if items else "empty", firstItems=len(items))
                if not items and self.resource_evidence is None:
                    self.resource_evidence = collect_resource_evidence(
                        self.broker, self.source_sha, target_node="C03")
            except (Invalid, ValueError, TypeError, KeyError):
                if not row["categoryCallId"]:
                    row["categoryCallId"] = len(self.calls)
                continue
            if not items or not details_sampled:
                continue
            selected = items[0]
            detail = row["detail"] = {"itemIdSha256": digest(selected["vod_id"]), "status": "error",
                                      "detailCallId": 0, "groups": 0, "episodes": 0}
            # A card's episode badge is advisory, and is not evidence of a playable episode list.
            badge = re.fullmatch(r"\s*(\d{1,6})\s*集\s*", str(selected.get("vod_remarks", "")))
            if badge:
                detail["listedEpisodes"] = int(badge[1])
            try:
                if selected["vod_id"] not in self.content_details:
                    yield from self.invoke("detail", {"ids": selected["vod_id"]})
                raw, call_id = self.content_details[selected["vod_id"]]
                detail["detailCallId"] = call_id
                _, details = cards(raw)
                require(len(details) == 1 and self.match_detail(selected, details[0])[0], "DETAIL_IDENTITY_MISMATCH")
                flags = details[0].get("vod_play_from", "")
                groups = details[0].get("vod_play_url", "")
                if not flags and not groups:
                    detail["status"] = "no_playback"
                    continue
                flags, groups = flags.split("$$$"), groups.split("$$$")
                require(len(flags) == len(groups) and all(flags) and all(groups), "PLAY_GROUPS_INVALID")
                counts = []
                for group in groups:
                    episodes = group.split("#")
                    for episode in episodes:
                        name, separator, value = episode.partition("$")
                        require(name.strip() and separator and value, "PLAY_EPISODE_INVALID")
                    counts.append(len(episodes))
                detail.update(status="returned", groups=len(groups), episodes=max(counts))
            except (Invalid, ValueError, TypeError, KeyError, AttributeError):
                if not detail["detailCallId"]:
                    detail["detailCallId"] = len(self.calls)
        self.content_sampled = True

    def partial_progress(self):
        value = {"schemaVersion": 1, "inputSourceSha256": self.source_sha,
                 "profileSha256": digest(self.profile) if self.profile is not None else None,
                 "fullAcceptance": False, "completedCalls": [
                     {key: row[key] for key in ("id", "method", "ok", "inputSha256", "outputSha256",
                                               "failureType", "failureContext") if key in row}
                     for row in self.calls]}
        if self.content_sampled:
            value["contentSnapshot"] = self.content_snapshot
        return json.loads(json.dumps(value))

    def record(self, node, status, reason, **values):
        if (reason in {"LOADER_INIT_FAILED", "LOADER_METHOD_FAILED"} and self.calls
                and not self.calls[-1]["ok"] and "failureType" in self.calls[-1]):
            values["failureType"] = self.calls[-1]["failureType"]
            if "failureContext" in self.calls[-1]:
                values["failureContext"] = dict(self.calls[-1]["failureContext"])
        self.nodes.append({"id": node, "status": status, "reason": reason, **values})
        if (self.resource_evidence is None and status in {"fail", "blocked"}
                and node in {"S06", "C01", "C02", "C03", "C07", "C10", "R01"}):
            self.resource_evidence = collect_resource_evidence(
                self.broker, self.source_sha, target_node=node)

    def run(self):
        home, items, detail, selection, player = None, [], None, None, None
        search_selected, search_detail = None, None
        detail_call_id, search_detail_call_id = 0, 0
        init_result = None
        try:
            payload = {"source": "/work/managed-source.py", "raw": True}
            if self.source_data:
                payload["data"] = self.source_data
            ext = base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode()
            init_result = yield from self.invoke("init", {"extend": ext})
            # Identity comes from the pre-execution hash handshake, not optional getName metadata.
            require(self.loaded and isinstance(init_result, dict) and set(init_result) == {"name", "result"}
                    and (init_result["name"] is None or isinstance(init_result["name"], str)),
                    "LOADER_INIT_IDENTITY_UNCONFIRMED")
            self.record("S06", "pass", "PRE_EXEC_SOURCE_SHA_AND_INIT_RETURNED",
                        initObservation=init_observation(init_result))
            self.initialization_contracts()
            self.summary["status"] = "init_returned"
        except (Invalid, ValueError, TypeError):
            self.record("S06", "blocked", "LOADER_INIT_FAILED",
                        initObservation=init_observation(init_result))
            return
        try:
            home = document((yield from self.invoke("home", {"filter": True})))
            home_call = len(self.calls)
            classes = home.get("class")
            require(isinstance(classes, list) and classes and all(
                isinstance(row, dict) and isinstance(row.get("type_id"), str) and row["type_id"]
                and isinstance(row.get("type_name"), str) and row["type_name"] for row in classes),
                "HOME_CATEGORIES_INVALID")
            require(len({row["type_id"] for row in classes}) == len(classes), "HOME_CATEGORY_IDS_DUPLICATED")
            self.summary["homeClasses"] = len(classes)
            self.summary["homeItems"] = len(home.get("list", []))
            self.record("C01", "pass", "HOME_CATEGORY_CONTRACT", classes=len(classes))
            if "S08" in self.required:
                hot = document((yield from self.invoke("home", {"filter": True})))
                require(hot.get("class") == classes, "HOT_HOME_CATEGORY_IDENTITY_CHANGED")
                self.hot_home = digest(classes)
        except (Invalid, ValueError, TypeError) as error:
            self.record("C01", "fail", str(error) if isinstance(error, Invalid) else "HOME_OUTPUT_INVALID")
        if self.mode == "cold":
            result = yield from self.invoke("destroy", {})
            require(result == {"status": "closed"}, "DESTROY_RETURN_INVALID")
            self.record("R09", "blocked", "DESTROY_RETURNED_PENDING_CONTAINER_CLEANUP")
            return
        if "C02" in self.targets:
            try:
                _, recommendations = cards(home, allow_duplicates=True)
                selected = recommendations[0]
                _, details = cards((yield from self.invoke("detail", {"ids": selected["vod_id"]})))
                require(len(details) == 1, "DETAIL_SELECTION_INVALID")
                matched, identity = self.match_detail(selected, details[0])
                require(matched, "DETAIL_IDENTITY_MISMATCH")
                self.record("C02", "pass", "HOME_RECOMMENDATION_TO_DETAIL_CONTRACT",
                            items=len(recommendations), homeCallId=home_call, detailCallId=len(self.calls),
                            identityEvidence=identity)
            except (Invalid, ValueError, TypeError, KeyError) as error:
                self.record("C02", "fail", str(error) if isinstance(error, Invalid) else "HOME_OUTPUT_INVALID")
        if home and self.summary["homeClasses"]:
            try:
                category = {"t": home["class"][0]["type_id"], "pg": "1", "filter": True, "extend": {}}
                first, items = cards((yield from self.invoke("category", category)))
                self.summary.update(categoryItems=len(items),
                                    categoryCovers=sum(bool(item.get("vod_pic")) for item in items),
                                    status="category_returned")
                mode = self.profile.get("paginationMode", "exact-total") if self.profile else "exact-total"
                if mode == "page-window":
                    def check_page(value, page):
                        reported = value.get("page")
                        # Window mode observes page changes, not advisory totals or page sizes.
                        if type(reported) is int or (
                                isinstance(reported, str) and reported.strip().isascii()
                                and reported.strip().isdecimal()):
                            reported = int(reported)
                            if reported > 0:
                                require(reported == page, "PAGINATION_REPLAY_OR_PAGE_MISMATCH")
                                return True
                        return False
                    page_numbers_verified = check_page(first, 1)
                    page2 = []
                    second_observed = first.get("hasNext") is not False and str(first.get("pagecount")) != "1"
                    if second_observed:
                        second, page2 = cards((yield from self.invoke("category", {**category, "pg": "2"})),
                                              allow_empty=True)
                        self.summary["page2Items"] = len(page2)
                        page_numbers_verified = check_page(second, 2) and page_numbers_verified
                        require({row["vod_id"] for row in items} != {row["vod_id"] for row in page2},
                                "PAGINATION_REPLAY_OR_PAGE_MISMATCH")
                    self.record("C03", "pass", "FIRST_SECOND_PAGE_WINDOW_CONTRACT",
                                firstItems=len(items), secondItems=len(page2),
                                pagesObserved=2 if second_observed else 1,
                                pageNumbersVerified=page_numbers_verified,
                                paginationMode=mode, exactTotalVerified=False, lastPageVerified=False)
                else:
                    total, limit, pages = (int(first[key]) for key in ("total", "limit", "pagecount"))
                    require(limit > 0 and len(items) == min(limit, total) and 1 <= pages <= 10000
                            and pages == math.ceil(total / limit), "PAGINATION_METADATA_INVALID")
                    require(int(first["page"]) == 1, "PAGINATION_REPLAY_OR_PAGE_MISMATCH")
                    page2 = []
                    if pages > 1:
                        second, page2 = cards((yield from self.invoke("category", {**category, "pg": "2"})))
                        self.summary["page2Items"] = len(page2)
                        require(int(second["page"]) == 2 and int(second["total"]) == total
                            and int(second["limit"]) == limit and int(second["pagecount"]) == pages
                            and len(page2) == min(limit, total - limit)
                            and not {row["vod_id"] for row in items}.intersection(row["vod_id"] for row in page2),
                            "PAGINATION_REPLAY_OR_PAGE_MISMATCH")
                    last, last_items = ((first, items) if pages == 1 else (second, page2) if pages == 2 else
                        cards((yield from self.invoke("category", {**category, "pg": str(pages)}))))
                    require(int(last["page"]) == pages and int(last["pagecount"]) == pages
                            and int(last["total"]) == total and int(last["limit"]) == limit
                            and len(last_items) == total - (pages - 1) * limit, "PAGINATION_LAST_PAGE_INVALID")
                    require(pages <= 2 or not {row["vod_id"] for row in last_items}.intersection(
                        row["vod_id"] for row in [*items, *page2]), "PAGINATION_REPLAY_OR_PAGE_MISMATCH")
                    self.record("C03", "pass", "FIRST_SECOND_LAST_PAGE_CONTRACT",
                                firstItems=len(items), secondItems=len(page2), lastItems=len(last_items), pages=pages)
            except (Invalid, ValueError, TypeError, KeyError) as error:
                values = {}
                hint = source_purpose_hint(self.source)
                if str(error) == "CONTENT_LIST_EMPTY_OR_INVALID" and hint:
                    values["sourcePurposeHint"] = hint
                self.record("C03", "fail", str(error) if isinstance(error, Invalid) else "CATEGORY_OUTPUT_INVALID",
                            **values)
                # Valid first-page cards still support independent detail/player observations.
        if "C05" in self.targets:
            keyword = (self.profile or {}).get("searchKeyword") or (items[0]["vod_name"][:200] if items else "")
            if not keyword.strip():
                self.record("C05", "blocked", "SEARCH_KEYWORD_REQUIRED")
            else:
                try:
                    _, found = cards((yield from self.invoke("search", {"wd": keyword, "quick": False, "pg": "1"})))
                    search_call = len(self.calls)
                    search_selected = found[0]
                    _, details = cards((yield from self.invoke("detail", {"ids": search_selected["vod_id"]})))
                    require(len(details) == 1, "DETAIL_SELECTION_INVALID")
                    matched, identity = self.match_detail(search_selected, details[0])
                    require(matched, "DETAIL_IDENTITY_MISMATCH")
                    search_detail = details[0]
                    search_detail_call_id = len(self.calls)
                    self.record("C05", "pass", "SEARCH_FIRST_PAGE_TO_DETAIL_CONTRACT", items=len(found),
                                searchCallId=search_call, detailCallId=len(self.calls),
                                keywordSha256=hashlib.sha256(keyword.encode()).hexdigest(),
                                identityEvidence=identity)
                except (Invalid, ValueError, TypeError, KeyError) as error:
                    self.record("C05", "fail", str(error) if isinstance(error, Invalid) else "SEARCH_OUTPUT_INVALID")
        media_targets = {"R06", *("M%02d" % i for i in range(1, 11))}
        player_requested = bool(self.targets.intersection({"R01", *media_targets}))
        detail_targets = {"C07", "C08", "C09", "C10", "C11", "R01", *media_targets}
        if (items or search_selected) and self.targets.intersection(detail_targets):
            identity = {}
            try:
                selected = items[0] if items else search_selected
                if search_detail is not None and selected["vod_id"] == search_selected["vod_id"]:
                    detail = search_detail
                    detail_call_id = search_detail_call_id
                else:
                    _, details = cards((yield from self.invoke("detail", {"ids": selected["vod_id"]})))
                    detail_call_id = len(self.calls)
                    require(len(details) == 1, "DETAIL_SELECTION_INVALID")
                    detail = details[0]
                matched, identity = self.match_detail(selected, detail)
                require(matched, "DETAIL_IDENTITY_MISMATCH")
                self.summary.update(detailItems=1, detailCovers=int(bool(detail.get("vod_pic"))),
                                    status="detail_returned")
                yield "detail", {"ids": ""}
                rejected = not self.response["ok"]
                if not rejected:
                    invalid = document(self.response["result"])
                    rejected = invalid.get("list") == []
                require(rejected, "INVALID_DETAIL_ID_RETURNED_CONTENT")
                self.record("C07", "pass", "LIST_ID_TO_DETAIL_AND_INVALID_ID_REJECTED", items=1,
                            identityEvidence=identity)
            except (Invalid, ValueError, TypeError) as error:
                self.record("C07", "fail", str(error) if isinstance(error, Invalid) else "DETAIL_OUTPUT_INVALID",
                            **({"identityEvidence": identity} if identity else {}))
        yield from self.sample_content(home)
        if detail and "C09" in self.targets:
            yield from self.sample_image(detail)
        if detail and (player_requested or "C10" in self.targets):
            playback_contract = {"detailCallId": detail_call_id, "parsedEpisodes": 0}
            try:
                flags, groups = detail["vod_play_from"].split("$$$"), detail["vod_play_url"].split("$$$")
                playback_contract.update(flagGroups=len(flags), urlGroups=len(groups),
                                         groupsAligned=len(flags) == len(groups) and all(bool(v) for v in flags + groups))
                require(len(flags) == len(groups) and all(flags) and all(groups), "PLAY_GROUPS_INVALID")
                episodes = []
                for group_index, (flag, group) in enumerate(zip(flags, groups)):
                    for episode_index, episode in enumerate(group.split("#")):
                        name, sep, ident = episode.partition("$")
                        playback_contract.update(groupIndex=group_index, episodeIndex=episode_index,
                                                 namePresent=bool(name.strip()), separatorPresent=bool(sep),
                                                 identifierPresent=bool(ident))
                        require(name.strip() and sep and ident, "PLAY_EPISODE_INVALID")
                        episodes.append((flag, ident))
                        playback_contract["parsedEpisodes"] = len(episodes)
                selection = episodes[0]
                self.summary.update(playGroups=len(flags), playEpisodes=len(episodes))
                self.record("C10", "pass", "ALL_GROUPS_AND_EPISODES_PARSED",
                            groups=len(flags), episodes=len(episodes), sampledPlayers=1)
            except (Invalid, KeyError, AttributeError) as error:
                self.record("C10", "fail", str(error) if isinstance(error, Invalid) else "PLAY_GROUPS_INVALID",
                            playbackContract=playback_contract)
        if selection and player_requested:
            contract = {}
            player_values = None
            try:
                player = document((yield from self.invoke("play", {"flag": selection[0], "play": selection[1]})))
                contract = player_contract(player)
                contract["playerContract"] = player_output_contract(player, selection[1], detail_call_id,
                                                                     self.calls[-1]["id"])
                player_values = {"detailCallId": detail_call_id, "playCallId": self.calls[-1]["id"],
                                 "selectedIdentifier": selection[1], "returnedUrl": player.get("url")}
                self.summary["playerUrlKind"] = (
                    contract["playerRouteKind"] if contract["playerRouteKind"] in {"http", "https", "absent"}
                    else "other")
                url = player.get("url")
                require(isinstance(url, str) and url and url != "about:blank", "PLAYER_URL_EMPTY")
                self.summary["playerStatus"] = "returned"
                if contract["offlineResolverRequired"]:
                    self.summary["playerParse"] = "parse_required"
                    self.record("R01", "pass", "SELECTED_PLAY_ID_TO_AT_OFFLINE_CONTRACT",
                                offlineDownloadVerified=False, clientPlaybackVerified=False, **contract)
                    # AT owns task creation and resolution; inspection must not start a production download.
                    for node in sorted(self.targets.intersection(media_targets)):
                        self.record(node, "blocked", "AT_OFFLINE_DOWNLOAD_NOT_VALIDATED")
                    player = None
                else:
                    parsed = urlsplit(url)
                    require(parsed.scheme in {"https", "http"} and parsed.hostname and not parsed.username
                            and not parsed.password and not parsed.fragment, "PLAYER_URL_INVALID")
                    headers = player.get("header", {})
                    if isinstance(headers, str):
                        headers = json.loads(headers)
                    require(isinstance(headers, dict) and all(isinstance(k, str) and isinstance(v, str)
                                                             for k, v in headers.items()), "PLAYER_HEADERS_INVALID")
                    player["header"] = headers
                    direct = contract["playerContract"]["parseMode"] == "direct"
                    if not direct and self.resource_evidence is None:
                        self.resource_evidence = collect_resource_evidence(
                            self.broker, self.source_sha, target_node="R01",
                            player_page={"url": url, "headers": headers}, player_values=player_values)
                    require(direct, "PLAYER_EXTERNAL_PARSER_REQUIRED")
                    self.summary.update(playerUrlKind=parsed.scheme, playerParse="direct")
                    self.record("R01", "pass", "SELECTED_PLAY_ID_TO_DIRECT_PLAYER_CONTRACT", **contract)
            except (Invalid, ValueError, TypeError) as error:
                if self.resource_evidence is None and player_values is not None:
                    self.resource_evidence = collect_resource_evidence(
                        self.broker, self.source_sha, target_node="R01", player_values=player_values)
                self.record("R01", "blocked", str(error) if isinstance(error, Invalid) else "PLAYER_OUTPUT_INVALID",
                            **contract)
                player = None
        if player and self.targets.intersection(media_targets):
            self.media(player)
        if "S07" in self.required:
            try:
                for method, params in (("", {}), ("home", []), ("loader_unknown_method", {})):
                    yield method, params
                    require(not self.response["ok"], "JSONL_INVALID_REQUEST_NOT_REJECTED")
                pong = yield from self.invoke("ping", {})
                require(pong == {"status": "ready"} and self.jsonl_observed,
                        "TOP_LEVEL_JSONL_NOT_CONFIRMED")
                self.record("S07", "blocked", "JSONL_ERRORS_OBSERVED_TIMEOUT_EXIT_PENDING",
                            rejectedRequests=3, strictFrameParsing=True, protocolChecksPassed=True)
            except Invalid as error:
                self.record("S07", "blocked", str(error))
        try:
            result = yield from self.invoke("destroy", {})
            require(result == {"status": "closed"}, "DESTROY_RETURN_INVALID")
            self.record("R09", "blocked", "DESTROY_RETURNED_PENDING_CONTAINER_CLEANUP")
        except Invalid:
            self.record("R09", "blocked", "DESTROY_UNCONFIRMED")

    def match_detail(self, selected, detail):
        identity = detail_identity(selected, detail)
        mapping = (self.profile or {}).get("detailIdentity", "source-navigation")
        matched = (mapping != "cctv-guid-cover" and identity["exactMatch"]
                   or mapping != "exact" and identity["guidCoverMatch"]
                   or mapping == "source-navigation" and identity["atvpDetailPrefixMatch"])
        return matched, identity

    def initialization_contracts(self):
        tree = ast.parse(self.source.decode("utf-8-sig"))
        top = [node for node in tree.body if isinstance(node, (ast.Import, ast.ImportFrom))]
        imports = [node for node in ast.walk(tree) if isinstance(node, (ast.Import, ast.ImportFrom))]
        dynamic = any(isinstance(node, ast.Call) and (
            isinstance(node.func, ast.Name) and node.func.id == "__import__"
            or isinstance(node.func, ast.Attribute) and node.func.attr in {"import_module", "exec_module"})
            for node in ast.walk(tree))
        absolute = all(not isinstance(node, ast.ImportFrom) or node.level == 0 for node in imports)
        complete = self.loaded and top == imports and absolute and not dynamic
        self.record("S04", "pass" if complete else "blocked",
                    "UNCONDITIONAL_IMPORTS_LOADED_IN_PINNED_IMAGE" if complete else "DEPENDENCY_COVERAGE_INCOMPLETE",
                    importStatements=len(imports), dynamicImportCoverage=False)
        spiders = [node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "Spider"]
        inits = [node for cls in spiders for node in cls.body
                 if isinstance(node, ast.FunctionDef) and node.name == "init"]
        simple = len(spiders) == len(inits) == 1
        if simple:
            init = inits[0]
            # Only literals, the plain extend argument and literal formatting may be printed.
            def printable(node):
                return (isinstance(node, ast.Constant) and isinstance(node.value, (str, int, bool, type(None)))
                        or isinstance(node, ast.Name) and node.id == "extend"
                        or isinstance(node, ast.Call) and not node.keywords
                        and isinstance(node.func, ast.Attribute) and node.func.attr == "format"
                        and isinstance(node.func.value, ast.Constant) and isinstance(node.func.value.value, str)
                        and all(printable(arg) for arg in node.args))

            simple = not init.decorator_list and all(
                isinstance(node, ast.Pass)
                or isinstance(node, ast.Expr) and (
                    isinstance(node.value, ast.Constant) and isinstance(node.value.value, str)
                    or isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name)
                    and node.value.func.id == "print" and not node.value.keywords
                    and all(printable(arg) for arg in node.value.args))
                for node in init.body)
        self.record("S05", "pass" if simple and self.loaded else "blocked",
                    "NO_USER_CONFIGURATION_REQUIRED_INIT_OBSERVED" if simple and self.loaded
                    else "USER_CONFIGURATION_CONTRACT_NOT_ESTABLISHED",
                    generatedRawExtObserved=True, userExtRequired=not bool(simple), credentialUse=False)

    def fetch(self, url, headers, ranged=False, byte_range=None):
        if ranged or byte_range is not None:
            headers = {key: value for key, value in headers.items() if key.lower() != "range"}
            start, end = byte_range if byte_range is not None else (0, 65535)
            headers["Range"] = "bytes=%d-%d" % (start, end)
        response = self.broker.fetch({"method": "GET", "url": url, "body": "",
                                      "headers": headers})
        require(response["status"] in {200, 206}, "RESOURCE_HTTP_FAILED")
        return response, base64.b64decode(response["body"], validate=True)

    def sample_image(self, detail):
        url = detail.get("vod_pic")
        parsed = urlsplit(url) if isinstance(url, str) else None
        if parsed is not None and parsed.scheme == "http" and parsed.netloc == "managed.invalid":
            yield from self._sample_declared_cover_proxy(detail, parsed)
            return
        yield from self._sample_direct_cover(detail)

    def _sample_direct_cover(self, detail):
        """Use a real direct response first, then ask the source-owned callback to decode it."""
        url = detail.get("vod_pic")
        direct = None
        try:
            direct, body = self.fetch(url, {})
            mime = next((value.split(";", 1)[0].strip().lower()
                         for key, value in direct["headers"].items()
                         if key.lower() == "content-type"), "")
            plain = (body.startswith(b"\xff\xd8\xff") or body.startswith(b"\x89PNG\r\n\x1a\n")
                     or body[:4] == b"RIFF" and body[8:12] == b"WEBP"
                     or body[:6] in {b"GIF87a", b"GIF89a"})
            verifier = getattr(self.broker, "verify_image", None)
            if not plain and verifier is not None:
                try:
                    decoded = verifier(body)
                    plain = (isinstance(decoded, dict)
                             and decoded.get("sha256") == hashlib.sha256(body).hexdigest()
                             and type(decoded.get("width")) is int and decoded["width"] > 0
                             and type(decoded.get("height")) is int and decoded["height"] > 0)
                except Exception:
                    plain = False
            if plain and mime.startswith("image/"):
                self.image(detail, response=direct)
                return
        except Exception:
            direct = None
        try:
            target = self._cover_proxy_target(url)
            params = {"target": target, "range": "", "head": False}
            result = yield from self.invoke("proxy", params)
            require(isinstance(result, dict) and set(result) == {
                "status", "mime", "headers", "bytes", "body", "sha256"}
                and result["status"] == 200 and isinstance(result["mime"], str)
                and isinstance(result["body"], str) and len(result["body"]) <= 4 * ((MAX_IMAGE_BYTES + 2) // 3)
                and type(result["bytes"]) is int, "COVER_PROXY_RESULT_INVALID")
            body = base64.b64decode(result["body"], validate=True)
            require(0 < len(body) <= MAX_IMAGE_BYTES and len(body) == result["bytes"]
                    and hashlib.sha256(body).hexdigest() == result["sha256"],
                    "COVER_PROXY_RESULT_INVALID")
            observation = {"workerCallId": self.calls[-1]["id"], "paramsSha256": digest(params),
                           "resultSha256": digest(result),
                           "targetSha256": hashlib.sha256(url.encode()).hexdigest()}
            self.image(detail, response={"status": 200, "body": result["body"],
                                        "headers": {"Content-Type": result["mime"]}},
                       proxy_observation=observation)
        except (Invalid, ValueError, TypeError):
            if direct is not None:
                self.image(detail, response=direct)
            else:
                self.record("C09", "blocked", "COVER_PROXY_RESULT_INVALID")

    @staticmethod
    def _cover_proxy_target(url):
        require(isinstance(url, str) and 0 < len(url) <= 8192, "COVER_PROXY_URL_INVALID")
        parsed = urlsplit(url)
        require(parsed.scheme in {"http", "https"} and parsed.hostname and not parsed.username
                and not parsed.password and not parsed.fragment, "COVER_PROXY_URL_INVALID")
        from urllib.parse import urlencode
        target = "/local?" + urlencode({"url": url})
        require(len(target) <= 8192, "COVER_PROXY_URL_INVALID")
        return target

    def _sample_declared_cover_proxy(self, detail, parsed):
        try:
            require(parsed.path == "/local" and not parsed.fragment, "COVER_PROXY_RESULT_INVALID")
            params = {"target": parsed.path + ("?" + parsed.query if parsed.query else ""),
                      "range": "", "head": False}
            result = yield from self.invoke("proxy", params)
            require(isinstance(result, dict) and set(result) == {
                "status", "mime", "headers", "bytes", "body", "sha256"}
                and result["status"] == 200 and isinstance(result["mime"], str)
                and isinstance(result["body"], str) and len(result["body"]) <= 4 * ((MAX_IMAGE_BYTES + 2) // 3)
                and type(result["bytes"]) is int, "COVER_PROXY_RESULT_INVALID")
            body = base64.b64decode(result["body"], validate=True)
            require(0 < len(body) <= MAX_IMAGE_BYTES and len(body) == result["bytes"]
                    and hashlib.sha256(body).hexdigest() == result["sha256"],
                    "COVER_PROXY_RESULT_INVALID")
            observation = {"workerCallId": self.calls[-1]["id"], "paramsSha256": digest(params),
                           "resultSha256": digest(result),
                           "targetSha256": hashlib.sha256(detail["vod_pic"].encode()).hexdigest()}
            self.image(detail, response={"status": 200, "body": result["body"],
                                         "headers": {"Content-Type": result["mime"]}},
                       proxy_observation=observation)
        except (Invalid, ValueError, TypeError):
            self.record("C09", "blocked", "COVER_PROXY_RESULT_INVALID")

    def image(self, detail, *, response=None, proxy_observation=None):
        image = None
        try:
            url = detail.get("vod_pic")
            require(isinstance(url, str) and url, "COVER_URL_EMPTY")
            if response is None:
                response, body = self.fetch(url, {})
            else:
                body = base64.b64decode(response["body"], validate=True)
            content_type = next((v.split(";", 1)[0].strip().lower()
                                 for k, v in response["headers"].items()
                                 if k.lower() == "content-type"), "")
            signature = ("jpeg" if body.startswith(b"\xff\xd8\xff") else
                         "png" if body.startswith(b"\x89PNG\r\n\x1a\n") else
                         "webp" if body[:4] == b"RIFF" and body[8:12] == b"WEBP" else
                         "gif" if body[:6] in {b"GIF87a", b"GIF89a"} else
                         "text" if body.lstrip()[:1] in {b"<", b"{", b"["} else "unknown")
            image = {"urlSha256": hashlib.sha256(url.encode()).hexdigest(),
                     "responseStatus": response["status"], "responseBytes": len(body),
                     "responseSha256": hashlib.sha256(body).hexdigest(),
                     "contentType": content_type if re.fullmatch(r"[a-z0-9.+/-]{0,100}", content_type) else "",
                     "prefixHex": body[:64].hex(), "suffixHex": body[-32:].hex(),
                     "signature": signature}
            verifier = getattr(self.broker, "verify_image", None)
            if verifier:
                decoded = verifier(body)
                require(isinstance(decoded, dict) and decoded.get("sha256") == hashlib.sha256(body).hexdigest()
                        and type(decoded.get("width")) is int and decoded["width"] > 0
                        and type(decoded.get("height")) is int and decoded["height"] > 0, "COVER_DECODER_IDENTITY_INVALID")
            else:
                decoded = decode_image(body)
            mime = next((v.split(";", 1)[0].lower() for k, v in response["headers"].items()
                         if k.lower() == "content-type"), "")
            require(mime.startswith("image/"), "COVER_MIME_INVALID")
            self.record("C09", "pass", "COVER_BYTES_DECODED", bytes=len(body),
                        responseSha256=hashlib.sha256(body).hexdigest(), width=decoded["width"],
                        height=decoded["height"])
            if proxy_observation is not None:
                self.nodes[-1]["proxyObservation"] = proxy_observation
        except subprocess.CalledProcessError:
            self.record("C09", "fail", "COVER_BYTES_INVALID")
        except Exception as error:
            reason = getattr(error, "code", None) or (
                str(error) if isinstance(error, Invalid) else "COVER_DECODE_UNAVAILABLE_OR_INVALID")
            self.record("C09", "fail" if reason in {
                "COVER_URL_EMPTY", "COVER_MIME_INVALID", "COVER_DIMENSIONS_INVALID"} else "blocked", reason)
        if (image and proxy_observation is None and self.nodes[-1]["status"] != "pass"
                and self.resource_evidence is None):
            self.resource_evidence = collect_resource_evidence(self.broker, self.source_sha, image)

    def media(self, player):
        try:
            register = getattr(self.broker, "register_player_resource", None)
            if register is not None:
                register(player["url"], player["header"])
            response, body = self.fetch(player["url"], player["header"], ranged=True)
            prefix = body.lstrip()
            kind = ("hls" if prefix.startswith(b"#EXTM3U") and b"#EXT" in prefix[7:]
                    else "mp4" if len(body) >= 12 and body[4:8] == b"ftyp"
                    else "mpeg_ts" if len(body) >= 377 and all(body[i] == 0x47 for i in (0, 188, 376))
                    else "unknown")
            require(kind != "unknown", "MEDIA_PREFIX_NOT_RECOGNIZED")
            self.summary["mediaStatus"] = "prefix_returned"
            self.record("M01", "pass", "HOST_FETCHED_PLAYER_RESOURCE", format=kind, bytes=len(body))
            self.record("R06", "pass", "PLAYER_HEADERS_USED_FOR_MEDIA_REQUEST",
                        headerNames=sorted(player["header"]))
            if kind == "hls":
                require(len(body) < 65536, "HLS_COMPLETE_DOCUMENT_NOT_CONFIRMED")
                self.hls(player["url"], body, player["header"])
            elif kind == "mp4" and "M06" in self.targets:
                self.mp4(player, response, body)
        except Exception as error:
            self.summary["mediaStatus"] = "http_failed"
            self.record("M01", "blocked", getattr(error, "code", None) or (
                str(error) if isinstance(error, Invalid) else "MEDIA_FETCH_FAILED"))

    def mp4(self, player, response, body):
        samples = []
        total = None
        try:
            def sample(start, response, body):
                nonlocal total
                require(response["status"] == 206, "MP4_RANGE_NOT_SUPPORTED")
                header = next((value for key, value in response["headers"].items()
                               if key.lower() == "content-range"), "")
                match = re.fullmatch(r"bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})", header)
                require(match is not None, "MP4_CONTENT_RANGE_INVALID")
                observed_start, end, size = map(int, match.groups())
                require(0 < size <= 2 ** 63 - 1, "MP4_CONTENT_RANGE_INVALID")
                require(total is None or size == total, "MP4_TOTAL_CHANGED")
                total = size
                require(observed_start == start and end == min(start + 65535, size - 1)
                        and len(body) == end - start + 1, "MP4_RANGE_MISMATCH")
                samples.append({"index": len(samples), "start": start, "end": end,
                                "bytes": len(body), "status": 206,
                                "responseSha256": hashlib.sha256(body).hexdigest()})

            sample(0, response, body)
            # A short resource's initial range already covers its first, middle and last bytes.
            starts = sorted({0, max(0, (total - 65536) // 2), max(0, total - 65536)})
            for start in starts[1:]:
                response, body = self.fetch(player["url"], player["header"],
                                            byte_range=(start, min(start + 65535, total - 1)))
                sample(start, response, body)
            self.record("M06", "pass", "MP4_COHERENT_RANGE_SAMPLES", format="mp4",
                        totalBytes=total, samples=samples, clientPlaybackVerified=False)
        except Exception as error:
            self.record("M06", "blocked", getattr(error, "code", None) or (
                str(error) if isinstance(error, Invalid) else "MP4_RANGE_FETCH_FAILED"), samples=samples,
                **({"totalBytes": total} if total is not None else {}))

    def hls(self, url, body, headers):
        active = "M02"
        keys = {}
        try:
            import m3u8
            marks = []
            key_declaration, map_declaration, current_map = 0, 0, None

            def track(line, lineno, data, state):
                nonlocal key_declaration, map_declaration, current_map
                if line.startswith("#EXT-X-KEY:"):
                    key_declaration += 1
                elif line.startswith("#EXT-X-MAP:"):
                    map_declaration += 1
                    current_map = (map_declaration, key_declaration, dict(state.get("current_key") or {}))
                elif line.startswith("#EXTINF:"):
                    marks.append((key_declaration, dict(state.get("current_key") or {}), current_map))

            playlist = m3u8.loads(body.decode("utf-8-sig"), uri=url, custom_tags_parser=track)
            if playlist.is_variant:
                require(bool(playlist.playlists), "HLS_MASTER_EMPTY")
                url = playlist.playlists[0].absolute_uri
                _, body = self.fetch(url, headers)
                require(body.lstrip().startswith(b"#EXTM3U") and len(body) < 65536,
                        "HLS_VARIANT_NOT_PLAYLIST")
                marks.clear()
                key_declaration, map_declaration, current_map = 0, 0, None
                playlist = m3u8.loads(body.decode("utf-8-sig"), uri=url, custom_tags_parser=track)
                require(not playlist.is_variant, "HLS_NESTED_MASTER_NOT_SUPPORTED")
                self.record("M02", "pass", "MASTER_TO_SELECTED_VARIANT_FETCHED", sampledVariants=1)
            else:
                self.record("M02", "pass", "DIRECT_MEDIA_PLAYLIST_NO_MASTER")
            active = "M03"
            segments = playlist.segments
            require(playlist.target_duration and playlist.target_duration > 0
                    and segments and len(marks) == len(segments)
                    and all(s.uri and s.duration and s.duration > 0 for s in segments),
                    "HLS_SEGMENTS_INVALID")
            require(not playlist.is_i_frames_only, "HLS_IFRAMES_ONLY_UNSUPPORTED")

            def interval(value, uri, previous=None):
                match = re.fullmatch(r"([0-9]{1,19})(?:@([0-9]{1,19}))?", value)
                require(match is not None, "HLS_BYTERANGE_INVALID")
                length = int(match[1])
                if match[2] is None:
                    require(previous is not None and previous[0] == uri, "HLS_BYTERANGE_INVALID")
                start = int(match[2]) if match[2] is not None else previous[1] + 1
                require(length > 0 and start + length <= 2 ** 63 - 1, "HLS_BYTERANGE_INVALID")
                return start, start + length - 1

            ranges, previous = [], None
            for segment in segments:
                selected = interval(segment.byterange, segment.absolute_uri, previous) if segment.byterange else None
                ranges.append(selected)
                previous = (segment.absolute_uri, selected[1]) if selected else None
            self.record("M03", "pass", "MEDIA_PLAYLIST_PARSED", segments=len(segments),
                        endList=bool(playlist.is_endlist), mediaSequence=playlist.media_sequence,
                        playlistSha256=hashlib.sha256(body).hexdigest())
            active = "M04"
            indexes = sorted({0, len(segments) // 2, len(segments) - 1})
            selected_indexes = sorted(set(indexes) | (set(range(min(3, len(segments))))
                                                      if "M07" in self.required else set()))
            resources, maps = [], {}

            def fetch_prefix(uri, selected=None):
                start, end = selected if selected else (0, 65535)
                end = min(end, start + 65535)
                response, prefix = self.fetch(uri, headers, byte_range=(start, end))
                complete = len(prefix) < 65536
                if selected or response["status"] == 206:
                    match = re.fullmatch(r"bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})", next(
                        (v for k, v in response["headers"].items() if k.lower() == "content-range"), ""))
                    require(response["status"] == 206 and match is not None, "HLS_RANGE_RESPONSE_INVALID")
                    observed_start, observed_end, total = map(int, match.groups())
                    require(0 < total <= 2 ** 63 - 1 and observed_start == start
                            and observed_end == min(end, total - 1)
                            and len(prefix) == observed_end - start + 1
                            and (selected is None or total > selected[1]), "HLS_RANGE_RESPONSE_INVALID")
                    complete = observed_end == (selected[1] if selected else total - 1)
                return response, prefix, complete

            def key_bytes(declaration, key):
                if key.get("method") in {None, "NONE"}:
                    return None
                require(key.get("method") == "AES-128" and key.get("keyformat", "identity") == "identity"
                        and "1" in (key.get("keyformatversions") or "1").split("/")
                        and isinstance(key.get("uri"), str) and key["uri"], "HLS_ENCRYPTION_UNSUPPORTED")
                if declaration not in keys:
                    target = urljoin(url, key["uri"])
                    response, value = self.fetch(target, headers, byte_range=(0, 16))
                    require(len(value) == 16 and (response["status"] == 200 or next(
                        (v for k, v in response["headers"].items() if k.lower() == "content-range"), "")
                        == "bytes 0-15/16"), "HLS_KEY_RESPONSE_INVALID")
                    keys[declaration] = bytearray(value)
                    resources.append({"kind": "key", "declaration": declaration, "bytes": 16,
                                      "status": response["status"], "urlSha256": hashlib.sha256(target.encode()).hexdigest(),
                                      "responseSha256": hashlib.sha256(value).hexdigest()})
                return keys[declaration]

            def decrypt(prefix, declaration, key, sequence, complete, initialization=False):
                secret = key_bytes(declaration, key)
                if secret is None:
                    return prefix, None
                from Crypto.Cipher import AES
                from Crypto.Util.Padding import unpad
                iv = key.get("iv")
                require(not initialization or iv is not None, "HLS_INIT_IV_REQUIRED")
                require(iv is None or re.fullmatch(r"0[xX][0-9a-fA-F]{1,32}", iv), "HLS_IV_INVALID")
                require(type(sequence) is int and 0 <= sequence < 2 ** 128, "HLS_IV_INVALID")
                count = len(prefix) if complete else len(prefix) // 16 * 16
                require(count > 0 and count % 16 == 0, "HLS_CIPHER_INVALID")
                try:
                    clear = AES.new(secret, AES.MODE_CBC, (int(iv[2:], 16) if iv else sequence).to_bytes(16, "big")).decrypt(prefix[:count])
                    if complete:
                        clear = unpad(clear, 16)
                except ValueError:
                    raise Invalid("HLS_DECRYPT_FAILED") from None
                return clear, {"method": "AES-128", "keyDeclaration": declaration,
                               "ivSource": "explicit" if iv else "media_sequence",
                               "prefixOnly": not complete, "paddingChecked": complete, "authenticated": False}

            def boxes(prefix, complete):
                offset, found = 0, set()
                while offset + 8 <= len(prefix):
                    size = int.from_bytes(prefix[offset:offset + 4], "big")
                    kind, header = prefix[offset + 4:offset + 8], 8
                    if size == 1:
                        require(offset + 16 <= len(prefix), "HLS_FMP4_INVALID")
                        size, header = int.from_bytes(prefix[offset + 8:offset + 16], "big"), 16
                    if size == 0:
                        require(complete, "HLS_FMP4_INVALID")
                        size = len(prefix) - offset
                    require(size >= header, "HLS_FMP4_INVALID")
                    if offset + size > len(prefix):
                        require(not complete and kind == b"mdat", "HLS_FMP4_INVALID")
                        found.add(kind)
                        return found
                    found.add(kind)
                    offset += size
                require(not complete or offset == len(prefix), "HLS_FMP4_INVALID")
                return found

            for index in selected_indexes:
                declaration, key, map_mark = marks[index]
                key_bytes(declaration, key)
                if map_mark is None or map_mark[0] in maps:
                    continue
                section = segments[index].init_section
                require(section is not None and section.uri, "HLS_INIT_INVALID")
                map_id, map_key_id, map_key = map_mark
                selected = interval(section.byterange, section.absolute_uri) if section.byterange else None
                response, prefix, complete = fetch_prefix(section.absolute_uri, selected)
                require(complete, "HLS_INIT_INCOMPLETE")
                clear, encryption = decrypt(prefix, map_key_id, map_key, 0, complete, initialization=True)
                require({b"ftyp", b"moov"} <= boxes(clear, True), "HLS_INIT_INVALID")
                item = {"kind": "init", "declaration": map_id, "bytes": len(prefix),
                        "status": response["status"], "urlSha256": hashlib.sha256(section.absolute_uri.encode()).hexdigest(),
                        "responseSha256": hashlib.sha256(prefix).hexdigest()}
                if selected:
                    item.update(start=selected[0], end=selected[0] + len(prefix) - 1)
                resources.append(item)
                maps[map_id] = item
            self.record("M04", "pass", "HLS_KEY_AND_INIT_RESOURCES_VERIFIED" if resources else
                        "NO_KEY_OR_INIT_RESOURCE_REQUIRED", resources=resources, keyBytesPersisted=False)

            def sample(index):
                response, prefix, complete = fetch_prefix(segments[index].absolute_uri, ranges[index])
                declaration, key, map_mark = marks[index]
                clear, encryption = decrypt(prefix, declaration, key, playlist.media_sequence + index, complete)
                if map_mark is not None:
                    require({b"moof", b"mdat"} <= boxes(clear, complete), "HLS_FMP4_INVALID")
                    container = "fmp4"
                else:
                    require(len(clear) >= 377 and all(clear[i] == 0x47 for i in (0, 188, 376)),
                            "HLS_SEGMENT_NOT_MPEG_TS")
                    container = "mpeg_ts"
                item = {"index": index, "bytes": len(prefix), "status": response["status"],
                        "responseSha256": hashlib.sha256(prefix).hexdigest(), "container": container,
                        "clearBytes": len(clear), "clearSha256": hashlib.sha256(clear).hexdigest()}
                if ranges[index]:
                    item.update(start=ranges[index][0], end=ranges[index][0] + len(prefix) - 1)
                if encryption:
                    item["encryption"] = encryption
                return item

            active = "M05"
            samples = [sample(index) for index in indexes]
            self.record("M05", "pass", "HLS_SEGMENT_SAMPLES_VERIFIED", samples=samples,
                        fullSegments=False, clientPlaybackVerified=False)
            if "M07" in self.required:
                active = "M07"
                start = time.monotonic()
                window = []
                for index, segment in enumerate(segments[:3]):
                    require(time.monotonic() - start < 10, "CONTINUOUS_WINDOW_DEADLINE")
                    window.append(sample(index))
                require(len(window) == min(3, len(segments)), "CONTINUOUS_WINDOW_INCOMPLETE")
                self.record("M07", "pass", "BOUNDED_CONSECUTIVE_SEGMENT_PREFIX_WINDOW",
                            samples=window, elapsedMillis=round((time.monotonic() - start) * 1000),
                            byteLimit=3 * 65536, timeLimitMillis=10000,
                            fullSegments=False, clientPlaybackVerified=False)
        except Exception as error:
            self.record(active, "blocked", getattr(error, "code", None) or (
                str(error) if isinstance(error, Invalid) else "HLS_PARSER_OR_RESOURCE_UNAVAILABLE"))
        finally:
            for value in keys.values():
                value[:] = bytes(len(value))
            keys.clear()

    def receipt(self):
        rows = {row["id"]: row for row in self.nodes}
        nodes = [rows.get(node, {"id": node, "status": "not_tested",
                                "reason": self.UNAVAILABLE.get(node, "NODE_NOT_VALIDATED_BY_THIS_RUNNER")})
                 for node in self.required]
        value = {"schemaVersion": 1, "authority": "host_loader_contract_observed",
                "scope": self.SCOPE, "inputSourceSha256": self.source_sha,
                "loadedTextSha256": self.text_sha if self.loaded else None,
                "runtimeSha256": self.runtime_sha, "requiredNodes": list(self.required),
                "requirementsFrozen": self.profile is not None,
                "profileSha256": digest(self.profile) if self.profile is not None else None,
                "sourceDataPresent": bool(self.source_data),
                "sourceDataSha256": digest(self.source_data),
                "nodes": nodes, "observedNodes": self.nodes,
                "hotHomeCategoriesSha256": self.hot_home,
                "calls": self.calls, "callsSha256": digest(self.calls),
                "successful": self.profile is not None and all(row["status"] == "pass" for row in nodes),
                "businessVerdictTrusted": False, "publishEligible": False,
                "boundary": "Host issued calls and validated returned contracts, not source self-reported status. "
                            "Source outputs remain untrusted; no AT production identity, client decode or long playback."}
        data = (self.profile or {}).get("publicSourceData", {})
        if all(key in data for key in SOURCE_IDENTITY_KEYS):
            value.update(self.source_identity)
        if self.content_snapshot is not None:
            value["contentSnapshot"] = self.content_snapshot
        return value

    @staticmethod
    def cleaned_receipt(receipt, cleaned):
        value = json.loads(json.dumps(receipt))
        require(cleaned == {"confirmed": True, "containerAbsent": True}, "CLEANUP_UNCONFIRMED")
        for rows in (value["nodes"], value["observedNodes"]):
            for row in rows:
                if row["id"] == "R09" and row["reason"] == "DESTROY_RETURNED_PENDING_CONTAINER_CLEANUP":
                    row.update(reason="NORMAL_CLEANUP_CONFIRMED_FAILURE_CASES_NOT_TESTED",
                               normalCleanupConfirmed=True)
        value["successful"] = False
        return value


class T4Call(Loader):
    """One credential-free RPC; a returned object is not a business acceptance verdict."""
    METHODS = {
        "home": (set(), {"filter"}),
        "category": ({"t", "pg"}, {"filter", "extend"}),
        "detail": ({"ids"}, set()),
        "search": ({"wd", "quick", "pg"}, set()),
        "play": ({"play", "flag"}, set()),
    }

    @staticmethod
    def validate_source_data(data, *, allow_empty=False):
        require(isinstance(data, dict) and len(data) <= 32
                and (allow_empty or 1 <= len(data)), "T4_RPC_SOURCE_DATA_INVALID")
        reserved = {"api", "source", "loader", "raw", "data", "extend", "preheaturl",
                    "t4playtype", "playbacksourcekind", "playbacksourcekey", "playbacksourcename",
                    "playbackconfigurl", "proxybase"}
        numeric = {"gateway_timeout", "gateway_total_timeout", "gateway_retries",
                   "detail_gateway_retries", "session_ttl_minutes"}
        for key, value in data.items():
            normalized = key.replace("_", "").lower() if isinstance(key, str) else ""
            require(isinstance(key, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", key)
                    and normalized not in reserved, "T4_RPC_SOURCE_DATA_INVALID")
            if key == "proxy_gateway":
                require(isinstance(value, str) and value.startswith(("http://", "https://"))
                        and not any(marker in value for marker in ("@", "?", "#", "\r", "\n"))
                        and len(value) <= 1024, "T4_RPC_SOURCE_DATA_INVALID")
                continue
            require(not any(part in normalized for part in (
                "token", "secret", "password", "cookie", "authorization", "proxy",
                "credential", "apikey")), "T4_RPC_SOURCE_DATA_INVALID")
            if key in numeric:
                require(type(value) is int and 0 <= value <= 100000,
                        "T4_RPC_SOURCE_DATA_INVALID")
            elif key == "subtitle_enabled":
                require(type(value) is bool, "T4_RPC_SOURCE_DATA_INVALID")
            else:
                require(isinstance(value, str) and len(value) <= 1024
                        and all(ord(ch) >= 32 and ord(ch) != 127 and not 0xD800 <= ord(ch) <= 0xDFFF
                                for ch in value), "T4_RPC_SOURCE_DATA_INVALID")
        require(len(json.dumps(data, ensure_ascii=True, sort_keys=True,
                               separators=(",", ":")).encode()) <= 8192, "T4_RPC_SOURCE_DATA_INVALID")

    @classmethod
    def validate_call(cls, call):
        require(isinstance(call, dict) and {"method", "params", "playType"} <= set(call)
                <= {"method", "params", "playType", "sourceData"}
                and isinstance(call["method"], str) and call["method"] in cls.METHODS
                and isinstance(call["playType"], str) and call["playType"] in {"t4", "t4-direct"}
                and isinstance(call["params"], dict), "T4_RPC_CALL_INVALID")
        if "sourceData" in call:
            cls.validate_source_data(call["sourceData"])
        params = call["params"]
        required, optional = cls.METHODS[call["method"]]
        require(required <= set(params) <= required | optional, "T4_RPC_PARAMS_INVALID")
        for key, value in params.items():
            if key in {"filter", "quick"}:
                require(type(value) is bool, "T4_RPC_PARAMS_INVALID")
            elif key == "extend":
                require(isinstance(value, dict) and len(value) <= 32
                        and all(isinstance(k, str) and 0 < len(k) <= 100
                                and isinstance(v, str) and len(v) <= 1024 for k, v in value.items()),
                        "T4_RPC_PARAMS_INVALID")
            else:
                require(isinstance(value, str) and 0 < len(value) <= 8192, "T4_RPC_PARAMS_INVALID")
                if key == "pg":
                    require(value.isascii() and value.isdecimal() and 1 <= len(value) <= 5
                            and 1 <= int(value) <= 10000, "T4_RPC_PARAMS_INVALID")
        require(len(json.dumps(call, ensure_ascii=True, separators=(",", ":")).encode()) <= 32768,
                "T4_RPC_CALL_LIMIT")

    def __init__(self, source, runtime_sha, broker, call):
        self.call = json.loads(json.dumps(call, ensure_ascii=True))
        self.validate_call(self.call)
        self.transient_result = None
        self.result_sha = None
        self.action_returned = False
        self.destroyed = False
        self.failure_code = None
        super().__init__(source, runtime_sha, broker)
        self.call_source_identity = validate_source_identity(
            source, self.call.get("sourceData", {}))

    def run(self):
        # Only explicitly approved public data accompanies the local source projection.
        payload = {"source": "/work/managed-source.py", "raw": True, "t4_play_type": self.call["playType"]}
        if "sourceData" in self.call:
            payload["data"] = self.call["sourceData"]
        extend = base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode()
        try:
            initialized = yield from self.invoke("init", {"extend": extend})
            require(self.loaded and isinstance(initialized, dict)
                    and isinstance(initialized.get("name"), str) and initialized["name"].strip(),
                    "T4_RPC_INIT_UNCONFIRMED")
            result = yield from self.invoke(self.call["method"], self.call["params"])
            require(isinstance(result, dict), "T4_RPC_RETURN_OBJECT_REQUIRED")
            self.transient_result = json.loads(json.dumps(result, ensure_ascii=True, allow_nan=False))
            self.result_sha = digest(self.transient_result)
            self.action_returned = True
            self.summary["status"] = "init_returned"
        except (Invalid, ValueError, TypeError):
            self.failure_code = "T4_RPC_METHOD_FAILED"
        # GeneratorExit must not issue another RPC; abrupt termination uses container cleanup.
        try:
            result = yield from self.invoke("destroy", {})
            require(result == {"status": "closed"}, "T4_RPC_DESTROY_UNCONFIRMED")
            self.destroyed = True
        except (Invalid, ValueError, TypeError):
            self.failure_code = "T4_RPC_DESTROY_UNCONFIRMED"

    def receipt(self):
        value = {"schemaVersion": 1, "authority": "host_t4_rpc_transport_observed",
                "inputSourceSha256": self.source_sha,
                "loadedTextSha256": self.text_sha if self.loaded else None,
                "runtimeSha256": self.runtime_sha, "method": self.call["method"],
                "playType": self.call["playType"], "paramsSha256": digest(self.call["params"]),
                "resultSha256": self.result_sha,
                "calls": self.calls, "callsSha256": digest(self.calls),
                "actionReturned": self.action_returned, "destroyReturned": self.destroyed,
                "initializationKind": "credential_free_local_source_projection",
                "sourceDataPresent": "sourceData" in self.call,
                "sourceDataSha256": digest(self.call.get("sourceData", {})),
                "fullGeneratedExtForwarded": False, "businessChainVerified": False,
                "productNodePassesAdded": 0, "publishEligible": False, "rawOutputStored": False}
        value.update(self.call_source_identity)
        return value

    def take_result(self):
        require(self.failure_code is None and self.loaded and self.jsonl_observed
                and self.action_returned and self.destroyed, "T4_RPC_INCOMPLETE")
        result, self.transient_result = self.transient_result, None
        require(result is not None, "T4_RPC_RESULT_ALREADY_TAKEN")
        return result


class SessionChannel:
    """One in-flight action and one claimable reply, never an action queue or durable body."""
    def __init__(self, initial, cancelled, publish, *, timeout=55):
        T4Call.validate_call(initial)
        require(type(timeout) in {int, float} and 0 < timeout <= 175,
                "T4_SESSION_TIMEOUT_INVALID")
        self.context = json.loads(json.dumps({key: initial[key] for key in ("playType", "sourceData") if key in initial}))
        self.condition = threading.Condition()
        self.cancelled, self.publish = cancelled, publish
        self.ordinal, self.claimed, self.pending = 0, True, None
        self.reply, self.closing, self.failed = None, False, False
        self.proxy_targets = set()
        self.deadline = time.monotonic() + timeout
        self.submit(1, initial)

    def register_proxy_manifest(self, params, result):
        mime = result.get("mime")
        if (not isinstance(mime, str) or mime.split(";", 1)[0].strip().lower() not in {
                "application/vnd.apple.mpegurl", "application/x-mpegurl"}
                or result.get("status") != 200 or params["head"]):
            return
        failure = "T4_SESSION_PROXY_DENIED"
        require(params["range"] in {"", "bytes=0-32767"}
                and not result.get("headers", {}).get("content-range")
                and isinstance(result.get("body"), str)
                and len(result["body"]) <= 4 * ((MAX_MANIFEST_BYTES + 2) // 3), failure)
        try:
            body = base64.b64decode(result["body"], validate=True)
            require(type(result.get("bytes")) is int and 0 < len(body) <= MAX_MANIFEST_BYTES
                    and result["bytes"] == len(body)
                    and result.get("sha256") == hashlib.sha256(body).hexdigest()
                    and body.lstrip().startswith(b"#EXTM3U"), failure)
            import m3u8
            text = body.decode("utf-8-sig")
            playlist = m3u8.loads(text, uri="http://managed.invalid" + params["target"])
            require(not playlist.is_variant and playlist.segments and not playlist.media
                    and not re.search(r"\bURI\s*=", text, re.IGNORECASE)
                    and all(key is None or key.method == "NONE" for key in playlist.keys)
                    and all(segment.byterange is None and segment.init_section is None
                            for segment in playlist.segments), failure)
            targets = []
            for segment in playlist.segments:
                address = segment.absolute_uri
                require(isinstance(address, str) and address.isascii() and len(address) <= 8192
                        and not re.search(r"[\x00-\x20\x7f\\]", address), failure)
                target = urlsplit(address)
                require(target.scheme == "http" and target.netloc == "managed.invalid"
                        and target.path == "/local" and not target.fragment, failure)
                query = parse_qsl(target.query, keep_blank_values=True,
                                  strict_parsing=True, max_num_fields=24)
                require(len({key for key, _ in query}) == len(query), failure)
                targets.append(target.path + ("?" + target.query if target.query else ""))
        except (ImportError, ValueError, UnicodeError):
            raise Invalid(failure) from None
        # Mirror the existing bounded HLS sampling: first three, midpoint and final segment.
        indexes = sorted(set(range(min(3, len(targets)))) | {len(targets) // 2, len(targets) - 1})
        selected = {targets[index] for index in indexes}
        with self.condition:
            require(not self.closing and not self.failed and not self.cancelled()
                    and time.monotonic() < self.deadline
                    and len(self.proxy_targets | selected) <= 16, failure)
            self.proxy_targets.update(selected)
        return sorted(selected)

    def submit(self, ordinal, call):
        if isinstance(call, dict) and call.get("method") == "proxy":
            require(set(call) == {"method", "params", *self.context}
                    and isinstance(call["params"], dict)
                    and set(call["params"]) == {"target", "range", "head"}
                    and isinstance(call["params"]["target"], str) and isinstance(call["params"]["range"], str)
                    and call["params"]["target"] in self.proxy_targets
                    and call["params"]["range"] in {"", "bytes=0-32767"}
                    and type(call["params"]["head"]) is bool, "T4_SESSION_PROXY_DENIED")
        else:
            T4Call.validate_call(call)
        require({key: call[key] for key in ("playType", "sourceData") if key in call} == self.context,
                "T4_SESSION_CONTEXT_CHANGED")
        with self.condition:
            require(not self.closing and not self.failed and not self.cancelled()
                    and time.monotonic() < self.deadline and self.claimed and self.pending is None
                    and type(ordinal) is int and ordinal == self.ordinal + 1 and ordinal <= 24,
                    "T4_SESSION_ACTION_UNAVAILABLE")
            self.ordinal, self.claimed = ordinal, False
            self.call_sha = digest(call)
            self.pending = json.loads(json.dumps(call))
            self.reply = None
            self.condition.notify_all()

    def next(self):
        with self.condition:
            while self.pending is None and not self.closing:
                require(not self.cancelled() and time.monotonic() < self.deadline, "T4_SESSION_EXPIRED")
                self.condition.wait(0.02)
            require(not self.cancelled(), "T4_SESSION_CANCELLED")
            call, self.pending = self.pending, None
            return call

    def receive(self, ordinal, call_sha):
        with self.condition:
            require(not self.closing and not self.failed and not self.cancelled()
                    and time.monotonic() < self.deadline and type(ordinal) is int and ordinal == self.ordinal
                    and call_sha == self.call_sha and not self.claimed, "T4_SESSION_RESULT_UNAVAILABLE")
            if self.reply is None:
                return {"ready": False}
            result, self.reply, self.claimed = self.reply, None, True
            return result

    def finish(self, result):
        with self.condition:
            require(not self.cancelled() and not self.closing and not self.failed
                    and not self.claimed and self.reply is None
                    and time.monotonic() < self.deadline, "T4_SESSION_CANCELLED")
            self.reply = result

    def close(self, failed=False, *, require_idle=False):
        with self.condition:
            if require_idle:
                require(not self.closing and not self.failed and not self.cancelled()
                        and time.monotonic() < self.deadline and self.claimed
                        and self.pending is None and self.reply is None, "T4_SESSION_ACTION_UNAVAILABLE")
            self.closing, self.failed = True, self.failed or failed
            self.pending = self.reply = None
            self.proxy_targets.clear()
            self.condition.notify_all()


class T4Session(T4Call):
    """Same JSONL worker and Spider instance until explicit close, failure, cancellation or TTL."""
    def __init__(self, source, runtime_sha, broker, channel):
        self.channel = channel
        self.action_receipts = []
        super().__init__(source, runtime_sha, broker, channel.pending)

    def run(self):
        payload = {"source": "/work/managed-source.py", "raw": True,
                   "t4_play_type": self.call["playType"]}
        if "sourceData" in self.call:
            payload["data"] = self.call["sourceData"]
        try:
            initialized = yield from self.invoke("init", {
                "extend": base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode()})
            require(self.loaded and isinstance(initialized, dict) and isinstance(initialized.get("name"), str)
                    and initialized["name"].strip(),
                    "T4_RPC_INIT_UNCONFIRMED")
            while True:
                call = self.channel.next()
                if call is None:
                    break
                params = call["params"]
                if call["method"] == "play":
                    params = {**params, "proxy_base": "http://managed.invalid"}
                result = yield from self.invoke(call["method"], params)
                require(isinstance(result, dict), "T4_RPC_RETURN_OBJECT_REQUIRED")
                result = json.loads(json.dumps(result, ensure_ascii=True, allow_nan=False))
                if call["method"] == "play" and isinstance(result.get("url"), str):
                    target = urlsplit(result["url"])
                    if target.scheme == "http" and target.netloc == "managed.invalid" and target.path == "/local":
                        require(len(result["url"]) <= 8192 and not target.fragment
                                and len(parse_qsl(target.query, keep_blank_values=True)) <= 24,
                                "T4_SESSION_PROXY_DENIED")
                        self.channel.proxy_targets.add(target.path + ("?" + target.query if target.query else ""))
                manifest_targets = (self.channel.register_proxy_manifest(call["params"], result)
                                    if call["method"] == "proxy" else None)
                observed = {"ordinal": self.channel.ordinal, "method": call["method"],
                            "t4CallSha256": digest(call), "paramsSha256": digest(call["params"]),
                            "resultSha256": digest(result), "workerCallId": self.calls[-1]["id"]}
                self.channel.publish(observed, result, manifest_targets)
                self.action_receipts.append(observed)
            destroyed = yield from self.invoke("destroy", {})
            require(destroyed == {"status": "closed"}, "T4_RPC_DESTROY_UNCONFIRMED")
            self.destroyed = True
        except (Invalid, ValueError, TypeError):
            self.channel.close(failed=True)
            self.failure_code = "T4_SESSION_FAILED"
            raise

    def receipt(self):
        require(self.destroyed and not self.channel.failed, "T4_SESSION_INCOMPLETE")
        value = {"schemaVersion": 1, "authority": "host_t4_session_observed",
                "inputSourceSha256": self.source_sha, "runtimeSha256": self.runtime_sha,
                "loadedTextSha256": self.text_sha, "initCount": 1, "destroyReturned": True,
                "playType": self.call["playType"],
                "sourceDataPresent": "sourceData" in self.call,
                "sourceDataSha256": digest(self.call.get("sourceData", {})),
                "initializationKind": "credential_free_local_source_projection",
                "fullGeneratedExtForwarded": False,
                "actions": self.action_receipts, "rawOutputStored": False,
                "businessChainVerified": False, "publishEligible": False}
        value.update(self.call_source_identity)
        return value
