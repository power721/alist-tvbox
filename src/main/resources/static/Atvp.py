# coding=utf-8
import base64
import html
import inspect
import json
import os
import re
import sys
import types
from abc import ABCMeta, abstractmethod
from importlib.machinery import SourceFileLoader
from pathlib import Path
from urllib.parse import unquote, urlsplit
from xml.etree import ElementTree

import requests
from Crypto.Cipher import AES
from Crypto.Hash import SHA256
from Crypto.Protocol.KDF import HKDF
from Crypto.PublicKey import ECC
from lxml import etree


class _FallbackSpider(metaclass=ABCMeta):
    _instance = None

    def __init__(self):
        self.extend = ''

    def __new__(cls, *args, **kwargs):
        if cls._instance:
            return cls._instance
        else:
            cls._instance = super().__new__(cls)
            return cls._instance

    @abstractmethod
    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        pass

    def homeVideoContent(self):
        pass

    def categoryContent(self, tid, pg, filter, extend):
        pass

    def detailContent(self, ids):
        pass

    def searchContent(self, key, quick, pg="1"):
        pass

    def playerContent(self, flag, id, vipFlags):
        pass

    def liveContent(self, url):
        pass

    def localProxy(self, param):
        pass

    def isVideoFormat(self, url):
        pass

    def manualVideoCheck(self):
        pass

    def action(self, action):
        pass

    def destroy(self):
        pass

    def getName(self):
        pass

    def getDependence(self):
        return []

    def loadSpider(self, name):
        return self.loadModule(name).Spider()

    def loadModule(self, name):
        path = os.path.join(os.getcwd(), f"{name}.py")
        return SourceFileLoader(name, path).load_module()

    def regStr(self, reg, src, group=1):
        m = re.search(reg, src)
        src = ''
        if m:
            src = m.group(group)
        return src

    def removeHtmlTags(self, src):
        clean = re.compile('<.*?>')
        return re.sub(clean, '', src)

    def cleanText(self, src):
        clean = re.sub('[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF]', '',
                       src)
        return clean

    def fetch(self, url, params=None, cookies=None, headers=None, timeout=5, verify=True, stream=False,
              allow_redirects=True):
        rsp = requests.get(url, params=params, cookies=cookies, headers=headers, timeout=timeout, verify=verify,
                           stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def post(self, url, params=None, data=None, json=None, cookies=None, headers=None, timeout=5, verify=True,
             stream=False, allow_redirects=True):
        rsp = requests.post(url, params=params, data=data, json=json, cookies=cookies, headers=headers, timeout=timeout,
                            verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def html(self, content):
        return etree.HTML(content)

    def str2json(str):
        return json.loads(str)

    def json2str(str):
        return json.dumps(str, ensure_ascii=False)

    def getProxyUrl(self, local=True):
        raise NotImplementedError("Proxy runtime is not available in local tests")

    def log(self, msg):
        if isinstance(msg, (dict, list)):
            print(json.dumps(msg, ensure_ascii=False))
        else:
            print(f"{msg}")

    def getCache(self, key):
        raise NotImplementedError("cache runtime is not available in local tests")

    def setCache(self, key, value):
        raise NotImplementedError("cache runtime is not available in local tests")

    def delCache(self, key):
        raise NotImplementedError("cache runtime is not available in local tests")


try:
    from base.spider import Spider as HostSpider
except Exception:
    HostSpider = _FallbackSpider


class _ModuleFilter:
    def __init__(self, module):
        self._module = module

    def __getattr__(self, name):
        return getattr(self._module, name)


class Spider(HostSpider):
    PUBLIC_KEY_XOR = 23
    MASTER_SECRET_XOR = 41
    DETAIL_PREFIX = "atvp_detail:"
    GROUP_PREFIX = "atvp_group:"
    RESUME_PREFIX = "atvp_resume:"
    PUSH_PREFIX = "push://"
    CHECK_LINK_HOSTS = (
        "alipan.com", "aliyundrive.com", "123pan.com", "123pan.cn",
        "123684.com", "123685.com", "123865.com", "123912.com", "123592.com",
        "123684.cn", "123685.cn", "123865.cn", "123912.cn", "123592.cn",
        "guangyapan.com", "mypikpak.com", "xunlei.com", "quark.cn", "139.com",
        "uc.cn", "115.com", "115cdn.com", "anxia.com", "189.cn", "baidu.com",
    )
    _LEADING_XML_DECL_RE = re.compile(r"<\?xml[^>]*\?>", re.I)
    _LEADING_HTML_TRIM_CHARS = "\ufeff" + "".join(chr(index) for index in range(33))
    _public_key_chunks = [
        "N0dCVVteVDdcUk46Ojo6Og==",
        "ZCBTJG0vKh06Ojo6OlJZUw==",
        "PFZOeHBnQWJ2f2dhe3RTIQ==",
        "cm8mWk1hXCNUQXJbWnlCfg==",
        "Rk5TXCVBYFZuUlZTXnFnIw==",
        "VDdcUk46Ojo6Oh1aVHhgVQ==",
        "Ojo6OjpVUlBeWTdHQlVbXg==",
    ]
    _master_secret_chunks = [
        "T0tPGg==",
        "T0wEER4QHQQdTEtMEEodTw==",
        "HxodEBlLSEoEERFIEQQdHA==",
    ]

    def __init__(self):
        super().__init__()
        self.name = "Atvp"
        self._inner = None
        self._backend_api = ""
        self._vod_token = ""
        self._localProxyConfig = {}
        self._localProxyBase = None
        self._detail_result_cache = {}
        self._search_keyword_cache = {}
        self._play_context_cache = {}
        self._resume_context_cache = {}
        self._filters = []

    def init(self, extend=""):
        self.extend = extend or ""
        payload = self._decode_ext_payload(self.extend)
        source, inner_extend = self._split_ext(self.extend)
        self._backend_api = self._resolve_backend_api(source, payload)
        self._vod_token = self._resolve_vod_token(payload)
        self.log(
            "Atvp link check configured: "
            f"api={self._backend_api or '-'}, token={'yes' if self._vod_token else 'no'}"
        )
        self._localProxyConfig = payload.get("local_proxy_config") if isinstance(payload, dict) else {}
        package_text = self._load_source(source)
        source_text = package_text if self._is_raw_source(payload) else self._decrypt_secspider_source(package_text)
        spider_cls = self._load_inner_spider_class(source_text)
        self._inner = spider_cls()
        result = self._inner.init(inner_extend)
        self._filters = self._load_filters(payload)
        return result

    def getName(self):
        if self._inner is not None and hasattr(self._inner, "getName"):
            return self._inner.getName()
        return self.name

    def _category_mode_enabled(self):
        if self._inner is None:
            return False
        return bool(getattr(self._inner, "backend_parse", False))

    def _split_ext(self, extend):
        raw = str(extend or "").strip()
        payload = self._decode_ext_payload(raw)
        if payload is not None:
            source = str(payload.get("source") or payload.get("api") or "").strip()
            return source, self._compose_inner_extend(payload)
        if "@@" not in raw:
            return raw, ""
        source, inner = raw.split("@@", 1)
        return source.strip(), inner

    def _decode_ext_payload(self, extend):
        raw = str(extend or "").strip()
        if not raw:
            return None
        try:
            decoded = base64.b64decode(raw).decode("utf-8")
            payload = json.loads(decoded)
        except Exception:
            return None
        if not isinstance(payload, dict):
            return None
        return payload if payload.get("source") or payload.get("api") else None

    def _is_raw_source(self, payload):
        return isinstance(payload, dict) and payload.get("raw") is True

    def _compose_inner_extend(self, payload):
        data_value = payload.get("data")
        token_value = str(payload.get("token") or "").strip()
        proxy_value = str(payload.get("local_proxy_config") or "").strip()

        extras = {}
        if token_value:
            extras["token"] = token_value
        if proxy_value:
            extras["local_proxy_config"] = proxy_value

        if data_value is None or data_value == "":
            return json.dumps(extras, ensure_ascii=False, separators=(",", ":")) if extras else ""

        if isinstance(data_value, dict):
            merged = dict(data_value)
            merged.update(extras)
            return json.dumps(merged, ensure_ascii=False, separators=(",", ":"))

        data_text = str(data_value)
        if not extras:
            return data_text

        try:
            parsed = json.loads(data_text)
        except Exception:
            parsed = None

        if isinstance(parsed, dict):
            merged = dict(parsed)
            merged.update(extras)
            return json.dumps(merged, ensure_ascii=False, separators=(",", ":"))

        payload_text = {"data": data_text}
        payload_text.update(extras)
        return json.dumps(payload_text, ensure_ascii=False, separators=(",", ":"))

    def _resolve_backend_api(self, source, payload):
        explicit_api = ""
        if isinstance(payload, dict) and payload.get("source"):
            explicit_api = str(payload.get("api") or "").strip()
        if explicit_api:
            return explicit_api.rstrip("/")
        remote_source = str(source or "").strip()
        if not self._is_remote_source(remote_source):
            return ""
        parsed = urlsplit(remote_source)
        if not parsed.scheme or not parsed.netloc:
            return ""
        return f"{parsed.scheme}://{parsed.netloc}".rstrip("/")

    def _resolve_vod_token(self, payload):
        if not isinstance(payload, dict):
            return ""
        token = str(payload.get("token") or "").strip()
        return "" if token == "-" else token

    def _build_backend_endpoint(self, path):
        backend_api = str(self._backend_api or "").rstrip("/")
        if not backend_api:
            raise ValueError(f"Atvp {path} backend api is empty")
        token = str(self._vod_token or "").strip()
        suffix = f"/{token}" if token else ""
        return f"{backend_api}/{path}{suffix}"

    def _is_remote_source(self, source):
        value = str(source or "").strip().lower()
        return value.startswith("http://") or value.startswith("https://")

    def _load_source(self, source):
        target = str(source or "").strip()
        if not target:
            raise ValueError("Atvp source is empty")
        if self._is_remote_source(target):
            rsp = self.fetch(target, timeout=10)
            body = str(rsp.text or "")
            if rsp.status_code != 200 or not body.strip():
                raise ValueError(f"Atvp remote source load failed: {target}")
            return body
        path = Path(target)
        if not path.is_file():
            raise ValueError(f"Atvp local source not found: {target}")
        return path.read_text(encoding="utf-8")

    @staticmethod
    def _obfuscate_text_for_test(text, xor_key, chunk_size=16):
        raw = str(text).encode("utf-8")
        chunks = [raw[index:index + chunk_size] for index in range(0, len(raw), chunk_size)]
        encoded = []
        for chunk in reversed(chunks):
            masked = bytes(byte ^ xor_key for byte in chunk)
            encoded.append(base64.b64encode(masked).decode("ascii"))
        return encoded

    def _deobfuscate_chunks(self, chunks, xor_key):
        decoded = []
        for chunk in reversed(list(chunks or [])):
            data = base64.b64decode(chunk)
            decoded.append(bytes(byte ^ xor_key for byte in data))
        return b"".join(decoded).decode("utf-8")

    def _strip_prefix(self, text, prefix):
        value = str(text or "")
        marker = str(prefix or "")
        if marker and value.startswith(marker):
            return value[len(marker):]
        return value

    def _parse_secspider_text(self, package_text):
        headers = {}
        payload = ""
        for line in str(package_text or "").splitlines():
            if line.startswith("//@"):
                key, _, value = line[3:].partition(":")
                headers[key] = value
            elif line.startswith("payload.base64:"):
                payload = self._strip_prefix(line, "payload.base64:")
        required = [
            "name",
            "version",
            "remark",
            "format",
            "alg",
            "wrap",
            "sign",
            "kid",
            "nonce",
            "ek",
            "hash",
            "sig",
        ]
        missing = [key for key in required if key not in headers]
        if missing or not payload:
            raise ValueError(f"Atvp secspider package is missing fields: {missing}")
        if headers["format"] != "secspider/1":
            raise ValueError("Atvp unsupported secspider format")
        if (
                headers["alg"] != "aes-256-gcm"
                or headers["wrap"] != "hkdf-aes-keywrap"
                or headers["sign"] != "ed25519"
        ):
            raise ValueError("Atvp unsupported secspider algorithm suite")
        return headers, payload

    def _build_signing_bytes(self, headers, payload_b64):
        lines = [
            f"//@name:{headers['name']}",
            f"//@version:{headers['version']}",
            f"//@remark:{headers['remark']}",
        ]
        if "id" in headers:
            lines.append(f"//@id:{headers['id']}")
        lines.extend(
            [
                f"//@format:{headers['format']}",
                f"//@alg:{headers['alg']}",
                f"//@wrap:{headers['wrap']}",
                f"//@sign:{headers['sign']}",
                f"//@kid:{headers['kid']}",
                f"//@nonce:{headers['nonce']}",
                f"//@ek:{headers['ek']}",
                f"//@hash:{headers['hash']}",
                f"payload.base64:{payload_b64}",
            ]
        )
        return "\n".join(lines).encode("utf-8")

    def _verify_signature(self, headers, payload_b64, public_key_text):
        from Crypto.Signature import eddsa

        verifier = eddsa.new(ECC.import_key(public_key_text), "rfc8032")
        verifier.verify(
            self._build_signing_bytes(headers, payload_b64),
            base64.b64decode(self._strip_prefix(headers["sig"], "base64:")),
        )

    def _decrypt_secspider_source(self, package_text):
        headers, payload_b64 = self._parse_secspider_text(package_text)
        public_key_text = self._deobfuscate_chunks(
            self._public_key_chunks,
            self.PUBLIC_KEY_XOR,
        )
        master_secret = self._deobfuscate_chunks(
            self._master_secret_chunks,
            self.MASTER_SECRET_XOR,
        ).encode("utf-8")
        try:
            self._verify_signature(headers, payload_b64, public_key_text)
        except ImportError:
            self.log("Atvp: Crypto.Signature.eddsa unavailable, skip secspider signature verification")
        wrap_key = HKDF(
            master=master_secret,
            key_len=32,
            salt=headers["kid"].encode("utf-8"),
            hashmod=SHA256,
            num_keys=1,
            context=f"secspider:{headers['name']}:{headers['version']}:wrap-key".encode("utf-8"),
        )
        wrap_nonce = HKDF(
            master=master_secret,
            key_len=12,
            salt=headers["kid"].encode("utf-8"),
            hashmod=SHA256,
            num_keys=1,
            context=f"secspider:{headers['name']}:{headers['version']}:wrap-nonce".encode("utf-8"),
        )
        wrap_blob = base64.b64decode(self._strip_prefix(headers["ek"], "base64:"))
        wrap_cipher = AES.new(wrap_key, AES.MODE_GCM, nonce=wrap_nonce)
        content_key = wrap_cipher.decrypt_and_verify(wrap_blob[:-16], wrap_blob[-16:])

        payload_blob = base64.b64decode(payload_b64)
        payload_nonce = base64.b64decode(self._strip_prefix(headers["nonce"], "base64:"))
        payload_cipher = AES.new(content_key, AES.MODE_GCM, nonce=payload_nonce)
        source_bytes = payload_cipher.decrypt_and_verify(payload_blob[:-16], payload_blob[-16:])
        source_hash = SHA256.new(source_bytes).hexdigest()
        if headers["hash"] != f"sha256:{source_hash}":
            raise ValueError("Atvp secspider source hash mismatch")
        return source_bytes.decode("utf-8")

    def _sanitize_html_content(self, content):
        if isinstance(content, bytes):
            text = None
            for encoding in (
                    "utf-8-sig",
                    "utf-8",
                    "utf-16",
                    "utf-16le",
                    "utf-16be",
                    "utf-32",
                    "utf-32le",
                    "utf-32be",
            ):
                try:
                    text = content.decode(encoding)
                    break
                except Exception:
                    continue
            if text is None:
                text = content.decode("utf-8", "ignore")
        elif isinstance(content, str):
            text = content
        elif content is None:
            text = ""
        else:
            text = str(content)

        text = text.replace("\x00", "")
        stripped = text.lstrip(self._LEADING_HTML_TRIM_CHARS)
        if not stripped:
            return stripped

        while stripped:
            match = self._LEADING_XML_DECL_RE.match(stripped)
            if match is None:
                break
            stripped = stripped[match.end():].lstrip(self._LEADING_HTML_TRIM_CHARS)
        return stripped

    def _patch_inner_spider_html(self, spider_cls):
        original_html = getattr(spider_cls, "html", None)
        outer = self

        def _wrapped_html(instance, content):
            sanitized = outer._sanitize_html_content(content)
            if callable(original_html):
                try:
                    return original_html(instance, sanitized)
                except etree.XMLSyntaxError as exc:
                    if "encoding not supported" not in str(exc):
                        raise
                    # original_html 即 etree.HTML(str)，走 lxml 的 _parseUnicodeDoc 路径；
                    # 部分 Chaquopy/libxml2 构建上会对含编码声明的 unicode 串误报
                    # "encoding not supported"（如 "USC4 little endian"）。原来的兜底再次
                    # 用同样的 str 调 etree.HTML，必然重蹈覆辙。这里改用 utf-8 字节走
                    # _parseMemoryDocument 路径（libxml2 自带 BOM/meta charset 检测）重试。
                    print("[ATVP] html bytes-retry head=%r" % sanitized[:80], file=sys.stderr)
                    return HostSpider.html(instance, sanitized.encode("utf-8"))
            return HostSpider.html(instance, sanitized)

        spider_cls.html = _wrapped_html
        return spider_cls

    def _load_inner_spider_class(self, source_text):
        module = types.ModuleType("atvp_inner_spider")
        module.__file__ = "<atvp-inner>"
        exec(compile(source_text, module.__file__, "exec"), module.__dict__)
        spider_cls = getattr(module, "Spider", None)
        if spider_cls is None:
            raise ValueError("Atvp inner source does not export Spider")
        return self._patch_inner_spider_html(spider_cls)

    def _load_filter_source(self, source):
        target = str(source or "").strip()
        if not target:
            raise ValueError("Atvp filter source is empty")
        if self._is_remote_source(target):
            rsp = self.fetch(target, timeout=10)
            body = str(rsp.text or "")
            if rsp.status_code != 200 or not body.strip():
                raise ValueError(f"Atvp filter remote source load failed: {target}")
            return body
        path = Path(target)
        if not path.is_file():
            raise ValueError(f"Atvp filter local source not found: {target}")
        return path.read_text(encoding="utf-8")

    def _load_filter_instance(self, source, index):
        source_text = self._load_filter_source(source)
        module = types.ModuleType(f"atvp_filter_{index}")
        module.__file__ = f"<atvp-filter-{index}>"
        exec(compile(source_text, module.__file__, "exec"), module.__dict__)
        filter_cls = getattr(module, "Filter", None) or getattr(module, "Decorator", None)
        if filter_cls is not None:
            return filter_cls()
        return _ModuleFilter(module)

    def _normalize_filter_stages(self, stages):
        if isinstance(stages, (list, tuple, set)):
            values = stages
        else:
            values = str(stages or "").split(",")
        result = []
        for value in values:
            stage = str(value or "").strip()
            if stage and stage not in result:
                result.append(stage)
        return result or ["detail"]

    def _filter_supports(self, filter_item, stage):
        stages = filter_item.get("stages") or []
        return "all" in stages or stage in stages

    def _filter_method(self, instance, stage):
        names = {
            "detail": ("detail", "detailContent"),
            "parse": ("parse",),
            "play": ("play",),
            "player": ("player", "playerContent"),
            "danmaku": ("danmaku",),
            "init": ("init",),
        }.get(stage, (stage,))
        for name in names:
            method = getattr(instance, name, None)
            if callable(method):
                return method
        return None

    def _invoke_filter_callable(self, func, *args):
        try:
            signature = inspect.signature(func)
        except (TypeError, ValueError):
            return func(*args)

        positional = [
            param
            for param in signature.parameters.values()
            if param.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD)
        ]
        has_varargs = any(param.kind == inspect.Parameter.VAR_POSITIONAL for param in signature.parameters.values())
        if has_varargs:
            return func(*args)
        return func(*args[:len(positional)])

    def _filter_label(self, filter_item):
        return str(filter_item.get("name") or filter_item.get("source") or "unknown")

    def _build_filter_context(self, stage, filter_item=None, context=None):
        payload = {
            "stage": stage,
            "api": self._backend_api,
            "token": self._vod_token,
            "local_proxy_config": self._localProxyConfig,
        }
        if filter_item is not None:
            payload["filter"] = {
                "name": filter_item.get("name"),
                "source": filter_item.get("source"),
                "stages": filter_item.get("stages"),
            }
        if isinstance(context, dict):
            payload.update(context)
        return payload

    def _init_filter(self, filter_item):
        method = self._filter_method(filter_item["instance"], "init")
        if method is None:
            return
        context = self._build_filter_context("init", filter_item)
        self._invoke_filter_callable(method, filter_item.get("data", ""), context)

    def _load_filters(self, payload):
        filters = payload.get("filters") if isinstance(payload, dict) else None
        if not isinstance(filters, list):
            return []

        result = []
        for index, entry in enumerate(filters, start=1):
            if not isinstance(entry, dict):
                continue
            source = str(entry.get("source") or "").strip()
            if not source:
                continue
            try:
                filter_item = {
                    "name": str(entry.get("name") or ""),
                    "source": source,
                    "stages": self._normalize_filter_stages(entry.get("stages")),
                    "data": entry.get("data", ""),
                    "error_strategy": str(entry.get("error_strategy") or entry.get("errorStrategy") or "skip").strip(),
                    "instance": self._load_filter_instance(source, index),
                }
                self._init_filter(filter_item)
                result.append(filter_item)
            except Exception as e:
                self.log(f"Atvp filter load failed: {source} {e}")
        return result

    def _run_filters(self, stage, result, context=None):
        output = result
        for filter_item in self._filters:
            if not self._filter_supports(filter_item, stage):
                continue
            method = self._filter_method(filter_item["instance"], stage)
            if method is None:
                continue
            try:
                value = self._invoke_filter_callable(
                    method,
                    output,
                    self._build_filter_context(stage, filter_item, context),
                )
                if value is not None:
                    output = value
            except Exception as e:
                self.log(f"Atvp filter {stage} failed: {self._filter_label(filter_item)} {e}")
                if filter_item.get("error_strategy") == "strict":
                    raise
        return output

    def _check_link_key(self, value):
        text = str(value or "").strip()
        if text.startswith(self.PUSH_PREFIX):
            text = text[len(self.PUSH_PREFIX):].strip()
        return text

    def _is_checkable_share_url(self, value):
        text = self._check_link_key(value).lower()
        if text.startswith("magnet:") or text.startswith("ed2k:"):
            return True
        try:
            host = urlsplit(text).hostname or ""
        except Exception:
            return False
        return any(fragment in host for fragment in self.CHECK_LINK_HOSTS)

    def _request_bad_links(self, candidates, source):
        items = []
        seen = set()
        for value in candidates:
            candidate = self._check_link_key(value)
            if candidate and candidate not in seen and self._is_checkable_share_url(candidate):
                seen.add(candidate)
                items.append({"url": candidate})
        if not items:
            self.log(f"Atvp link check skipped: source={source}, reason=no supported links")
            return set()
        if not self._backend_api:
            self.log(f"Atvp link check skipped: source={source}, reason=backend api is empty")
            return set()

        try:
            endpoint = self._build_backend_endpoint("check-links")
            self.log(
                f"Atvp link check request: source={source}, count={len(items)}, api={self._backend_api}"
            )
            response = self.post(
                endpoint,
                json={"items": items},
                timeout=15,
            )
            if getattr(response, "status_code", 0) != 200:
                self.log(
                    f"Atvp link check failed: source={source}, status={getattr(response, 'status_code', 0)}"
                )
                return set()
            try:
                response_payload = response.json()
            except Exception:
                response_payload = json.loads(str(getattr(response, "text", "") or ""))
            bad = {
                self._check_link_key(entry.get("url"))
                for entry in response_payload.get("results", [])
                if isinstance(entry, dict) and entry.get("state") == "bad"
                and self._check_link_key(entry.get("url"))
            } if isinstance(response_payload, dict) else set()
            self.log(
                f"Atvp link check response: source={source}, count={len(items)}, bad={len(bad)}"
            )
            return bad
        except Exception as exc:
            self.log(f"Atvp link check failed: {exc}")
            return set()

    def _check_detail_links(self, result):
        """Remove only links explicitly reported as bad by the token check API."""
        if not isinstance(result, dict):
            return result

        candidates = []
        vod_list = result.get("list")
        if not isinstance(vod_list, list):
            return result
        for vod in vod_list:
            if not isinstance(vod, dict):
                continue
            for group in str(vod.get("vod_play_url") or "").split("$$$"):
                for episode in str(group or "").split("#"):
                    _, _, target = episode.partition("$")
                    candidate = self._check_link_key(target or episode)
                    candidates.append(candidate)
            groups = vod.get("group")
            if isinstance(groups, list):
                for folder in groups:
                    if not isinstance(folder, dict) or not isinstance(folder.get("media"), list):
                        continue
                    for media in folder["media"]:
                        candidate = self._check_link_key(media.get("url") if isinstance(media, dict) else "")
                        candidates.append(candidate)
        bad = self._request_bad_links(candidates, "detail")
        if not bad:
            return result

        payload = dict(result)
        filtered_vods = []
        for original_vod in vod_list:
            if not isinstance(original_vod, dict):
                filtered_vods.append(original_vod)
                continue
            vod = dict(original_vod)
            from_groups = str(vod.get("vod_play_from") or "").split("$$$")
            url_groups = str(vod.get("vod_play_url") or "").split("$$$")
            kept_urls, kept_from = [], []
            for index, url_group in enumerate(url_groups):
                episodes = []
                for episode in str(url_group or "").split("#"):
                    _, _, target = episode.partition("$")
                    if self._check_link_key(target or episode) not in bad:
                        episodes.append(episode)
                if episodes:
                    kept_urls.append("#".join(episodes))
                    kept_from.append(from_groups[index] if index < len(from_groups) else "")
            if "vod_play_url" in vod:
                vod["vod_play_url"] = "$$$".join(kept_urls)
                if "vod_play_from" in vod:
                    vod["vod_play_from"] = "$$$".join(kept_from)
            groups = vod.get("group")
            if isinstance(groups, list):
                kept_groups = []
                for folder in groups:
                    if not isinstance(folder, dict) or not isinstance(folder.get("media"), list):
                        kept_groups.append(folder)
                        continue
                    updated = dict(folder)
                    updated["media"] = [
                        media for media in folder["media"]
                        if not isinstance(media, dict)
                        or self._check_link_key(media.get("url")) not in bad
                    ]
                    if updated["media"]:
                        kept_groups.append(updated)
                vod["group"] = kept_groups
            filtered_vods.append(vod)
        payload["list"] = filtered_vods
        return payload

    def _check_search_links(self, result):
        if not isinstance(result, dict) or not isinstance(result.get("list"), list):
            return result

        candidates_by_item = []
        for item in result["list"]:
            candidates = []
            if isinstance(item, dict):
                for field in ("vod_id", "url", "share_url"):
                    if item.get(field):
                        candidates.append(item[field])
            candidates_by_item.append(candidates)

        bad = self._request_bad_links(
            (candidate for candidates in candidates_by_item for candidate in candidates),
            "search",
        )
        if not bad:
            return result

        payload = dict(result)
        payload["list"] = [
            item for item, candidates in zip(result["list"], candidates_by_item)
            if not any(self._check_link_key(candidate) in bad for candidate in candidates)
        ]
        return payload

    def _require_inner(self):
        if self._inner is None:
            raise RuntimeError("Atvp spider is not initialized")
        return self._inner

    def _decode_parse(self, vod_id):
        value = str(vod_id or "").strip()
        if value.startswith(self.PUSH_PREFIX):
            value = value[len(self.PUSH_PREFIX):].strip()
        if (value.startswith("http://") or value.startswith("https://")
                or value.startswith("magnet:") or value.startswith("ed2k:")):
            return value
        return None

    def _resolve_deferred_share_url(self, source_id, share_url):
        original = str(share_url or "").strip()
        context = self._lookup_play_context(source_id)
        play_id = str(context.get("play_id") or "").strip()
        if not play_id.startswith(self.PUSH_PREFIX):
            return original

        player = getattr(self._require_inner(), "playerContent", None)
        if not callable(player):
            return original
        try:
            result = player(str(context.get("play_from") or ""), play_id, [])
        except Exception as exc:
            self.log(f"Atvp deferred share resolve failed: {exc}")
            return original

        if isinstance(result, str):
            try:
                result = json.loads(result)
            except Exception:
                return original
        if not isinstance(result, dict):
            return original

        resolved = str(result.get("url") or "").strip()
        if resolved.startswith(self.PUSH_PREFIX):
            resolved = resolved[len(self.PUSH_PREFIX):].strip()
        return self._decode_parse(resolved) or original

    def _encode_category_id(self, vod_id):
        return self.DETAIL_PREFIX + vod_id

    def _encode_group_id(self, source_id, group_index):
        payload = json.dumps(
            {"id": str(source_id or ""), "group": int(group_index)},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        encoded = base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")
        return self.GROUP_PREFIX + encoded

    def _decode_group_id(self, category_id):
        value = str(category_id or "").strip()
        if not value.startswith(self.GROUP_PREFIX):
            return None
        try:
            encoded = value[len(self.GROUP_PREFIX):]
            encoded += "=" * (-len(encoded) % 4)
            payload = json.loads(base64.urlsafe_b64decode(
                encoded.encode("ascii")
            ).decode("utf-8"))
            source_id = str(payload.get("id") or "")
            group_index = int(payload.get("group"))
        except (AttributeError, TypeError, ValueError, json.JSONDecodeError):
            return None
        if not source_id or group_index < 0:
            return None
        return source_id, group_index

    def _encode_resume_id(self, context):
        payload = {
            "id": str(context.get("id") or ""),
            "playlist": int(context.get("playlist") or 0),
        }
        for key in ("group", "source", "subgroup"):
            value = context.get(key)
            if value is not None:
                payload[key] = int(value)
        name = str(context.get("subgroupName") or "").strip()
        if name:
            payload["subgroupName"] = name
        encoded = base64.urlsafe_b64encode(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        ).decode("ascii").rstrip("=")
        return self.RESUME_PREFIX + encoded

    def _decode_resume_id(self, vod_id):
        value = str(vod_id or "").strip()
        if not value.startswith(self.RESUME_PREFIX):
            return None
        try:
            encoded = value[len(self.RESUME_PREFIX):]
            encoded += "=" * (-len(encoded) % 4)
            payload = json.loads(base64.urlsafe_b64decode(
                encoded.encode("ascii")
            ).decode("utf-8"))
            source_id = str(payload.get("id") or "")
            playlist_index = int(payload.get("playlist"))
        except (AttributeError, TypeError, ValueError, json.JSONDecodeError):
            return None
        if not source_id or playlist_index < 0:
            return None
        context = {"id": source_id, "playlist": playlist_index}
        # 跨端同步坐标(atv-player 多级导航);旧格式 resume id 没有这些键。
        for key in ("group", "source", "subgroup"):
            value = payload.get(key)
            if value is None:
                continue
            try:
                context[key] = int(value)
            except (TypeError, ValueError):
                continue
        name = str(payload.get("subgroupName") or "").strip()
        if name:
            context["subgroupName"] = name
        return context

    def _resume_entries(self, vod):
        """按分组返回网盘资源链接:[[group0 的 media url...], [group1 的...]]。

        无有效资源的分组不产生条目,与 atv-player 侧过滤空分组后的坐标一致。
        """
        entries = []
        groups = vod.get("group") if isinstance(vod, dict) else None
        if isinstance(groups, list):
            for group in groups:
                if not isinstance(group, dict) or not isinstance(group.get("media"), list):
                    continue
                media_urls = []
                for media in group["media"]:
                    if not isinstance(media, dict):
                        continue
                    target = str(media.get("url") or "").strip()
                    if target:
                        media_urls.append(target)
                if media_urls:
                    entries.append(media_urls)
            if entries:
                return entries

        for url_group in str(vod.get("vod_play_url") or "").split("$$$"):
            selected = ""
            for episode in str(url_group or "").split("#"):
                label, separator, target = str(episode or "").partition("$")
                candidate = str(target if separator else label).strip()
                if candidate:
                    selected = candidate
                    break
            if selected:
                entries.append([selected])
        return entries

    def _select_resume_target(self, vod, context):
        """优先用跨端坐标 (group, source) 定位网盘资源;旧格式/坐标越界退回平铺 playlist。"""
        entries = self._resume_entries(vod)
        group_index = context.get("group")
        source_index = context.get("source")
        if group_index is not None and 0 <= group_index < len(entries):
            media_urls = entries[group_index]
            if source_index is None:
                source_index = 0
            if 0 <= source_index < len(media_urls):
                return media_urls[source_index]
        targets = [target for media_urls in entries for target in media_urls]
        playlist_index = int(context.get("playlist") or 0)
        if playlist_index >= len(targets):
            raise ValueError(f"Atvp resume source not found: {context.get('id')}")
        return targets[playlist_index]

    def _remember_resume_contexts(self, vod):
        if not isinstance(vod, dict):
            return
        source_id = str(vod.get("vod_id") or "").strip()
        decoded = self._decode_resume_id(source_id)
        if decoded is not None:
            source_id = decoded["id"]
        if not source_id:
            return
        flat_index = 0
        for group_index, media_urls in enumerate(self._resume_entries(vod)):
            for source_index, target in enumerate(media_urls):
                value = str(target or "").strip()
                if value.startswith(self.PUSH_PREFIX):
                    value = value[len(self.PUSH_PREFIX):].strip()
                share_url = self._decode_parse(value)
                if share_url is not None:
                    self._resume_context_cache[share_url] = {
                        "id": source_id,
                        "playlist": flat_index,
                        "group": group_index,
                        "source": source_index,
                    }
                flat_index += 1

    def _apply_resume_context(self, result, context):
        vod_list = result.get("list") if isinstance(result, dict) else None
        if not isinstance(vod_list, list):
            return result
        payload = dict(result)
        encoded_id = self._encode_resume_id(context)
        payload["list"] = [
            dict(vod, vod_id=encoded_id) if isinstance(vod, dict) else vod
            for vod in vod_list
        ]
        return payload

    def _reorder_resume_lines(self, parsed_result, context):
        """跨端续播时把记录中的子目录线路排到第一位。

        宿主(FongMi)按 vodFlag 匹配线路,匹配不上就回退第一条线路;
        服务端线路名经公共前后缀裁剪(如 "S02【2019】"→"2【2019"),与记录里的
        真实目录名常常对不上,不调序就会回落到第一个子目录。
        """
        if not isinstance(parsed_result, dict):
            return parsed_result
        vod_list = parsed_result.get("list")
        if not isinstance(vod_list, list):
            return parsed_result
        subgroup_index = context.get("subgroup")
        subgroup_name = str(context.get("subgroupName") or "").strip()
        if subgroup_index is None and not subgroup_name:
            return parsed_result
        payload = dict(parsed_result)
        payload["list"] = [
            self._reorder_vod_lines(vod, subgroup_index, subgroup_name)
            if isinstance(vod, dict) else vod
            for vod in vod_list
        ]
        return payload

    def _reorder_vod_lines(self, vod, subgroup_index, subgroup_name):
        from_groups = str(vod.get("vod_play_from") or "").split("$$$")
        url_groups = str(vod.get("vod_play_url") or "").split("$$$")
        if len(from_groups) != len(url_groups) or len(from_groups) < 2:
            return vod
        target = None
        if subgroup_index is not None and 0 <= int(subgroup_index) < len(from_groups):
            target = int(subgroup_index)
        else:
            for index, flag in enumerate(from_groups):
                if str(flag or "").strip() and str(flag or "").strip() == subgroup_name:
                    target = index
                    break
        if target is None or target == 0:
            return vod
        order = [target] + [index for index in range(len(from_groups)) if index != target]
        updated = dict(vod)
        updated["vod_play_from"] = "$$$".join(from_groups[index] for index in order)
        updated["vod_play_url"] = "$$$".join(url_groups[index] for index in order)
        return updated

    def _parse(self, share_url, resume_context=None):
        api = self._build_backend_endpoint("parse")
        share_url = str(share_url or "").strip()
        cached_vod = self._detail_result_cache.get(share_url)
        title = str(cached_vod.get("vod_name") or "").strip() if isinstance(cached_vod, dict) else ""
        request = {"url": share_url}
        if title:
            request["title"] = title
        keyword = self._search_keyword_cache.get(share_url, "")
        if keyword:
            request["keyword"] = keyword
        rsp = self.post(api, json=request, params={"ac": "play"}, timeout=10)
        if rsp.status_code != 200:
            return self.PUSH_PREFIX + share_url
        body = str(rsp.text or "")
        if rsp.status_code != 200 or not body.strip():
            raise ValueError(f"Atvp parse failed: {share_url}")
        parsed_result = json.loads(body)
        parsed_result = self._merge_cached_detail_result(share_url, parsed_result)
        parsed_result = self._run_filters("parse", parsed_result, {"share_url": share_url})
        parsed_result = self._run_filters("detail", parsed_result, {"share_url": share_url, "source": "parse"})
        parsed_result = self._check_detail_links(parsed_result)
        context = resume_context or self._resume_context_cache.get(share_url)
        if context is not None:
            parsed_result = self._reorder_resume_lines(parsed_result, context)
            parsed_result = self._apply_resume_context(parsed_result, context)
        if isinstance(parsed_result, dict):
            parsed_result = dict(parsed_result)
            parsed_result["_atvp_backend_parse"] = True
        self._cache_detail_result(parsed_result, keyword)
        self._cache_play_context(parsed_result)
        return parsed_result

    def _remember_search_keyword(self, value, keyword):
        value = str(value or "").strip()
        keyword = str(keyword or "").strip()
        if not value or not keyword:
            return
        self._search_keyword_cache[value] = keyword
        if value.startswith(self.DETAIL_PREFIX):
            self._search_keyword_cache[value[len(self.DETAIL_PREFIX):]] = keyword
        decoded = unquote(value)
        if decoded != value:
            self._search_keyword_cache[decoded] = keyword

    def _remember_result_keywords(self, result, keyword):
        vod_list = result.get("list") if isinstance(result, dict) else None
        if not isinstance(vod_list, list):
            return
        for vod in vod_list:
            if isinstance(vod, dict):
                self._remember_search_keyword(vod.get("vod_id"), keyword)

    def _cache_detail_result(self, detail_result, keyword=None):
        vod_list = detail_result.get("list") if isinstance(detail_result, dict) else None
        if not isinstance(vod_list, list) or len(vod_list) != 1:
            return

        vod = vod_list[0]
        if not isinstance(vod, dict):
            return

        def remember(target):
            target = str(target or "").strip()
            if target.startswith(self.PUSH_PREFIX):
                target = target[len(self.PUSH_PREFIX):].strip()
            share_url = self._decode_parse(target)
            if share_url is not None:
                self._detail_result_cache[share_url] = dict(vod)
                self._remember_search_keyword(share_url, keyword)

        for url_group in str(vod.get("vod_play_url") or "").split("$$$"):
            for episode in str(url_group or "").split("#"):
                _, _, target = episode.partition("$")
                remember(target or episode)

        for group in vod.get("group") or []:
            if not isinstance(group, dict):
                continue
            for media in group.get("media") or []:
                if isinstance(media, dict):
                    remember(media.get("url"))

    def _cache_play_context(self, detail_result):
        vod_list = detail_result.get("list") if isinstance(detail_result, dict) else None
        if not isinstance(vod_list, list):
            return

        total = 0
        for vod in vod_list:
            if not isinstance(vod, dict):
                continue
            self._remember_resume_contexts(vod)
            vod_name = str(vod.get("vod_name") or "").strip()
            from_groups = str(vod.get("vod_play_from") or "").split("$$$")
            url_groups = str(vod.get("vod_play_url") or "").split("$$$")
            cached_count = 0
            for group_index, url_group in enumerate(url_groups):
                play_from = from_groups[group_index] if group_index < len(from_groups) else ""
                for episode_index, episode in enumerate(str(url_group or "").split("#"), start=1):
                    label, _, target = str(episode or "").partition("$")
                    play_id = str(target or label or "").strip()
                    if not play_id:
                        continue
                    episode_name = str(label or "").strip()
                    context = {
                        "vod_name": vod_name,
                        "vod_pic": vod.get("vod_pic", ""),
                        "vod_year": vod.get("vod_year", ""),
                        "vod_remarks": vod.get("vod_remarks", ""),
                        "type_name": vod.get("type_name", ""),
                        "episode_name": episode_name,
                        "episode_index": episode_index,
                        "play_from": play_from,
                        "play_id": play_id,
                    }
                    self._remember_play_context(play_id, context)
                    cached_count += 1
            if cached_count:
                total += cached_count
                self.log(
                    "Atvp filter play context cached: "
                    f"vod_name={vod_name or '-'}, count={cached_count}"
                )
        if total:
            self.log(f"Atvp filter play context cache ready: total={total}")

    def _remember_play_context(self, play_id, context):
        value = str(play_id or "").strip()
        if not value:
            return
        self._play_context_cache[value] = dict(context)
        if value.startswith(self.PUSH_PREFIX):
            self._play_context_cache[value[len(self.PUSH_PREFIX):]] = dict(context)

    def _lookup_play_context(self, play_id):
        value = str(play_id or "").strip()
        if not value:
            return {}
        context = self._play_context_cache.get(value)
        if context is None and value.startswith(self.PUSH_PREFIX):
            context = self._play_context_cache.get(value[len(self.PUSH_PREFIX):])
        if context is None:
            if self._filters:
                self.log(f"Atvp filter play context missing: id={self._short_log_value(value)}")
            return {}
        return dict(context)

    def _short_log_value(self, value, limit=80):
        text = str(value or "")
        if len(text) <= limit:
            return text
        return text[:limit] + "..."

    def _build_player_context(self, flag=None, play_id=None, vip_flags=None):
        context = {
            "flag": flag,
            "id": play_id,
            "vipFlags": vip_flags,
        }
        play_context = self._lookup_play_context(play_id)
        if play_context:
            context.update(play_context)
            context["play"] = play_context
            self.log(
                "Atvp filter play context resolved: "
                f"vod_name={play_context.get('vod_name') or '-'}, "
                f"episode={play_context.get('episode_name') or '-'}, "
                f"play_from={play_context.get('play_from') or '-'}"
            )
        return context

    def _merge_cached_detail_vod(self, cached_vod, parsed_vod):
        merged = dict(parsed_vod)
        for key in (
                "vod_name",
                "vod_pic",
                "vod_year",
                "vod_director",
                "vod_actor",
                "vod_area",
                "vod_lang",
                "type_name",
                "vod_douban_score",
                "vod_content",
        ):
            value = cached_vod.get(key)
            if value not in (None, ""):
                merged[key] = value
        return merged

    def _merge_cached_detail_result(self, share_url, parsed_result):
        cached_vod = self._detail_result_cache.get(str(share_url or "").strip())
        vod_list = parsed_result.get("list") if isinstance(parsed_result, dict) else None
        if not isinstance(cached_vod, dict) or not isinstance(vod_list, list):
            return parsed_result

        payload = dict(parsed_result)
        payload["list"] = [
            self._merge_cached_detail_vod(cached_vod, vod)
            if isinstance(vod, dict) else vod
            for vod in vod_list
        ]
        return payload

    def _play(self, play_id):
        proxy_type = ""
        if self._localProxyConfig:
            proxy_type = "client-proxy"
        rsp = self.fetch(
            self._build_backend_endpoint("play"),
            params={"id": str(play_id or ""), "type": proxy_type},
            timeout=10,
        )
        body = str(rsp.text or "")
        if rsp.status_code != 200 or not body.strip():
            raise ValueError(f"Atvp play failed: {play_id}")
        # 播放结果的本地代理改写在 playerContent 出口统一做(与 Java 侧 PyProxy 行为对等)
        return self._run_filters("play", json.loads(body), self._build_player_context(play_id=play_id))

    def _resolve_local_proxy_base(self):
        # 探测同设备 spring.jar(全局 spider)里 VideoStreamProxy 的监听端口(5000 起找可用,可能漂移)。
        # 未运行(如播放器没加载 spring.jar)时返回 None,由调用方降级直连。失败结果短缓存,避免每次播放都全端口扫。
        if self._localProxyBase is not None:
            return self._localProxyBase or None
        for port in range(5000, 5010):
            base = f"http://127.0.0.1:{port}"
            try:
                rsp = self.fetch(base + "/status", timeout=1)
                if rsp.status_code == 200:
                    self._localProxyBase = base
                    self.log(f"Atvp local proxy detected at {base}")
                    return base
            except Exception:
                continue
        self._localProxyBase = ""
        return None

    def _apply_local_proxy(self, result, play_id):
        # 把播放结果交给 VideoStreamProxy 的 /player 接口改写:url/multiUrls 换成本地分片代理地址(多账号加速)。
        # 任何失败(未配置/无代理/非200/坏JSON)原样返回,降级语义与 Java 侧 proxyPlayerContent 一致;
        # parse!=0、天翼、未启用盘位等跳过逻辑由 /player 内部处理。
        if not isinstance(result, dict) or not self._localProxyConfig:
            return result
        if not str(result.get("url") or "").startswith(("http://", "https://")):
            return result
        base = self._resolve_local_proxy_base()
        if not base:
            return result
        try:
            task_seed = str(play_id or "task").replace("/", "_")
            rsp = self.post(base + "/player", json={
                "playerContent": json.dumps(result, ensure_ascii=False),
                "taskSeed": task_seed,
                "localProxyConfig": self._localProxyConfig,
            }, timeout=10)
            if rsp.status_code != 200:
                return result
            proxied = rsp.json()
            return proxied if isinstance(proxied, dict) else result
        except Exception as e:
            self.log(f"Atvp local proxy rewrite failed: {e}")
            return result


    def _empty_category_result(self):
        return {
            "list": [],
            "page": 1,
            "pagecount": 1,
            "limit": 0,
            "total": 0,
        }

    def _category_result(self, items):
        return {
            "list": items,
            "page": 1,
            "pagecount": 1,
            "limit": len(items),
            "total": len(items),
        }

    def _load_category_detail_vod(self, source_id):
        detail_result = self._require_inner().detailContent([source_id])
        detail_result = self._run_filters("detail", detail_result, {"ids": [source_id], "source": "category"})
        detail_result = self._check_detail_links(detail_result)
        keyword = self._search_keyword_cache.get(str(source_id or "").strip(), "")
        self._cache_detail_result(detail_result, keyword)
        self._cache_play_context(detail_result)
        vod_list = detail_result.get("list") if isinstance(detail_result, dict) else None
        if not isinstance(vod_list, list) or len(vod_list) != 1:
            return None

        vod = vod_list[0]
        if not isinstance(vod, dict):
            return None
        return vod

    def _group_folder_items(self, source_id, vod):
        groups = vod.get("group")
        if not isinstance(groups, list):
            return []

        items = []
        for group_index, group in enumerate(groups):
            if not isinstance(group, dict):
                continue
            media = group.get("media")
            if not isinstance(media, list) or not media:
                continue
            resource_count = sum(
                1 for item in media
                if isinstance(item, dict) and str(item.get("url") or "").strip()
            )
            if not resource_count:
                continue
            group_name = str(group.get("name") or "资源").strip() or "资源"
            items.append({
                "vod_id": self._encode_group_id(source_id, group_index),
                "vod_name": f"{group_name} ({resource_count})",
                "vod_pic": vod.get("vod_pic", ""),
                "vod_remarks": vod.get("vod_remarks", ""),
                "vod_tag": "folder",
            })
        return items

    def _split_group_to_vods(self, source_id, group_index):
        vod = self._load_category_detail_vod(source_id)
        if vod is None:
            return self._empty_category_result()

        groups = vod.get("group")
        if not isinstance(groups, list) or group_index >= len(groups):
            return self._empty_category_result()
        group = groups[group_index]
        if not isinstance(group, dict) or not isinstance(group.get("media"), list):
            return self._empty_category_result()

        items = []
        for media in group["media"]:
            if not isinstance(media, dict):
                continue
            target = str(media.get("url") or "").strip()
            if not target:
                continue
            if target.startswith(self.PUSH_PREFIX):
                target = target[len(self.PUSH_PREFIX):]
            items.append({
                "vod_id": target,
                "vod_name": str(media.get("name") or group.get("name") or "资源").strip() or "资源",
                "vod_pic": vod.get("vod_pic", ""),
                "vod_remarks": vod.get("vod_remarks", ""),
                "vod_tag": "file",
            })
        return self._category_result(items)

    def _split_detail_to_vods(self, source_id):
        vod = self._load_category_detail_vod(source_id)
        if vod is None:
            return self._empty_category_result()

        group_items = self._group_folder_items(source_id, vod)
        if group_items:
            return self._category_result(group_items)

        play_from_value = str(vod.get("vod_play_from") or "")
        play_url_value = str(vod.get("vod_play_url") or "")
        if not play_from_value or not play_url_value:
            return self._empty_category_result()

        from_groups = play_from_value.split("$$$")
        url_groups = play_url_value.split("$$$")
        if len(from_groups) != len(url_groups):
            return self._empty_category_result()

        items = []
        for index, (from_group, url_group) in enumerate(zip(from_groups, url_groups), start=1):
            label, _, target = str(url_group or "").partition("$")
            if target.startswith(self.PUSH_PREFIX):
                target = target[len(self.PUSH_PREFIX):]
            line_name = from_group or label
            item = {
                "vod_id": target,
                "vod_name": line_name,
                "vod_pic": vod.get("vod_pic", ""),
                "vod_remarks": vod.get("vod_remarks", ""),
                "vod_tag": "file",
            }
            items.append(item)
        return self._category_result(items)

    def _normalize_category_content(self, result):
        if not isinstance(result, dict):
            return result
        vod_list = result.get("list")
        if not isinstance(vod_list, list):
            return result

        normalized = []
        for vod in vod_list:
            if not isinstance(vod, dict):
                normalized.append(vod)
                continue
            item = dict(vod)
            tag = item.get("vod_tag", "")
            if tag != "folder":
                item["vod_id"] = self._encode_category_id(item.get("vod_id", ""))
                item["vod_tag"] = "folder"
            normalized.append(item)

        payload = dict(result)
        payload["list"] = normalized
        return payload

    def homeContent(self, filter):
        result = self._require_inner().homeContent(filter)
        if not self._category_mode_enabled():
            return result
        return self._normalize_category_content(result)

    def homeVideoContent(self):
        result = self._require_inner().homeVideoContent()
        if not self._category_mode_enabled():
            return result
        return self._normalize_category_content(result)

    def categoryContent(self, tid, pg, filter, extend):
        print('categoryContent', tid, pg, filter, extend)
        if not self._category_mode_enabled():
            return self._require_inner().categoryContent(tid, pg, filter, extend)
        group_target = self._decode_group_id(tid)
        if group_target is not None:
            return self._split_group_to_vods(*group_target)
        if tid.startswith(self.DETAIL_PREFIX):
            tid = tid[len(self.DETAIL_PREFIX):]
            return self._split_detail_to_vods(tid)
        result = self._require_inner().categoryContent(tid, pg, filter, extend)
        self._remember_result_keywords(result, self._search_keyword_cache.get(str(tid or "").strip(), ""))
        return self._normalize_category_content(result)

    def detailContent(self, ids):
        print('detailContent', ids)
        if isinstance(ids, (list, tuple)) and len(ids) == 1:
            raw_id = str(ids[0] or "").strip()
            resume_context = self._decode_resume_id(raw_id)
            if resume_context is not None:
                direct_share_url = self._decode_parse(resume_context["id"])
                if direct_share_url is not None:
                    return self._parse(direct_share_url, resume_context)
                vod = self._load_category_detail_vod(resume_context["id"])
                share_url = self._decode_parse(self._select_resume_target(vod, resume_context))
                if share_url is None:
                    raise ValueError(f"Atvp resume source is not a drive link: {resume_context['id']}")
                return self._parse(share_url, resume_context)
            share_url = self._decode_parse(raw_id)
            if share_url is not None:
                resolved_url = self._resolve_deferred_share_url(raw_id, share_url)
                if resolved_url != share_url and share_url in self._detail_result_cache:
                    self._detail_result_cache[resolved_url] = self._detail_result_cache[share_url]
                return self._parse(resolved_url)
        result = self._require_inner().detailContent(ids)
        keyword = self._search_keyword_cache.get(str(ids[0] if ids else "").strip(), "")
        result = self._run_filters("detail", result, {"ids": ids})
        result = self._check_detail_links(result)
        self._cache_detail_result(result, keyword)
        self._cache_play_context(result)
        return result

    def searchContent(self, key, quick, pg="1"):
        print('searchContent', key, quick, pg)
        result = self._require_inner().searchContent(key, quick, int(pg))
        self._remember_result_keywords(result, key)
        result = self._check_search_links(result)
        if not self._category_mode_enabled():
            return result
        return self._normalize_category_content(result)

    def _is_qqmusic_qrc_xml(self, text):
        value = str(text or "").strip()
        return value.startswith("<?xml") and "<QrcInfos" in value and "LyricContent=" in value

    def _qq_qrc_xml_to_apk_lrc(self, xml_text):
        value = str(xml_text or "").strip()
        if not value:
            return ""
        match = re.search(r'<Lyric_1\b[^>]*\bLyricContent="(.*?)"\s*/>', value, re.S)
        if match:
            raw = match.group(1)
        else:
            try:
                root = ElementTree.fromstring(value)
            except ElementTree.ParseError:
                return ""
            lyric_node = root.find(".//Lyric_1")
            if lyric_node is None:
                return ""
            raw = lyric_node.get("LyricContent", "")

        text = html.unescape(raw)
        text = text.replace("\r\n", "\n").replace("\r", "\n")

        lines = []
        for raw_line in text.split("\n"):
            line = raw_line.strip()
            if not line:
                continue
            if line.startswith(("[ti:", "[ar:", "[al:", "[by:", "[offset:")):
                continue
            if re.match(r"^\[\d+,\d+\].*\(\d+,\d+\)", line):
                lines.append(line)
        return "\n".join(lines)

    def _normalize_lyric_value(self, lyric_value):
        if isinstance(lyric_value, dict):
            text = str(lyric_value.get("text") or "")
            if self._is_qqmusic_qrc_xml(text):
                return self._qq_qrc_xml_to_apk_lrc(text) or text
            return text
        return str(lyric_value or "")

    def _normalize_player_content(self, result):
        if not isinstance(result, dict):
            return result

        payload = dict(result)

        lyric_value = payload.pop("lyric", None)
        if "lrc" not in payload and lyric_value is not None:
            payload["lrc"] = self._normalize_lyric_value(lyric_value)

        cover_value = payload.pop("cover", None)
        if "artwork" not in payload and cover_value is not None:
            payload["artwork"] = cover_value

        qualities_value = payload.pop("qualities", None)
        if qualities_value is not None:
            if isinstance(qualities_value, dict):
                qualities_value = [qualities_value]
            urls = []
            for entry in qualities_value:
                if not isinstance(entry, dict):
                    continue
                quality = str(entry.get("quality") or entry.get("label") or "")
                url = str(entry.get("url") or "")
                if not quality or not url:
                    continue
                urls.extend([quality, url])
            if urls:
                payload["url"] = urls

        return payload

    def playerContent(self, flag, id, vipFlags):
        print('playerContent', flag, id, vipFlags)
        vid = str(id)
        if vid.startswith("1@"):
            result = self._play(vid)
        else:
            result = self._require_inner().playerContent(flag, id, vipFlags)
            result = self._normalize_player_content(result)
        result = self._run_filters("player", result, self._build_player_context(flag, id, vipFlags))
        # 出口统一过本地分片代理(后端 1@ 线路与插件自身直链都覆盖,与 Java 侧 PyProxy 对等)
        return self._apply_local_proxy(result, vid)

    def liveContent(self, url):
        return self._require_inner().liveContent(url)

    def localProxy(self, param):
        return self._require_inner().localProxy(param)

    def isVideoFormat(self, url):
        return self._require_inner().isVideoFormat(url)

    def manualVideoCheck(self):
        return self._require_inner().manualVideoCheck()

    def action(self, action):
        return self._require_inner().action(action)

    def getCache(self, key):
        return self._require_inner().getCache(key)

    def setCache(self, key, value):
        return self._require_inner().setCache(key, value)

    def delCache(self, key):
        return self._require_inner().delCache(key)

    def getProxyUrl(self, local=True):
        inner = self._require_inner()
        if hasattr(inner, "getProxyUrl"):
            return inner.getProxyUrl(local)
        return super().getProxyUrl(local)

    def getDependence(self):
        if self._inner is None:
            return []
        if hasattr(self._inner, "getDependence"):
            return self._inner.getDependence()
        return []

    def danmaku(self):
        inner = self._require_inner()
        enabled = False
        if hasattr(inner, "danmaku"):
            enabled = bool(inner.danmaku())
        context = self._build_filter_context("danmaku")
        for filter_item in self._filters:
            if not self._filter_supports(filter_item, "danmaku"):
                continue
            method = self._filter_method(filter_item["instance"], "danmaku")
            if method is None:
                enabled = True
                continue
            try:
                value = self._invoke_filter_callable(method, context)
                if value is not None:
                    enabled = enabled or bool(value)
            except Exception as e:
                self.log(f"Atvp filter danmaku failed: {self._filter_label(filter_item)} {e}")
                if filter_item.get("error_strategy") == "strict":
                    raise
        return enabled

    def getManagerActions(self):
        inner = self._require_inner()
        if hasattr(inner, "getManagerActions"):
            return inner.getManagerActions()
        return []

    def runManagerAction(self, action_id, context):
        inner = self._require_inner()
        if hasattr(inner, "runManagerAction"):
            return inner.runManagerAction(action_id, context)
        raise ValueError(f"unsupported action: {action_id}")

    def runPlayerAction(self, action_id, context):
        inner = self._require_inner()
        if hasattr(inner, "runPlayerAction"):
            return inner.runPlayerAction(action_id, context)
        return {"actions": []}

    def destroy(self):
        if self._inner is not None and hasattr(self._inner, "destroy"):
            return self._inner.destroy()
        return None
