# coding=utf-8
import base64
import html
import inspect
import json
import os
import re
import types
from abc import ABCMeta, abstractmethod
from importlib.machinery import SourceFileLoader
from pathlib import Path
from urllib.parse import urlsplit
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
        self.extend = ""

    def __new__(cls, *args, **kwargs):
        if cls._instance:
            return cls._instance
        cls._instance = super().__new__(cls)
        return cls._instance

    @abstractmethod
    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        raise NotImplementedError

    def homeVideoContent(self):
        raise NotImplementedError

    def categoryContent(self, tid, pg, filter, extend):
        raise NotImplementedError

    def detailContent(self, ids):
        raise NotImplementedError

    def searchContent(self, key, quick, pg="1"):
        raise NotImplementedError

    def playerContent(self, flag, id, vipFlags):
        raise NotImplementedError

    def liveContent(self, url):
        raise NotImplementedError

    def localProxy(self, param):
        raise NotImplementedError

    def isVideoFormat(self, url):
        raise NotImplementedError

    def manualVideoCheck(self):
        raise NotImplementedError

    def action(self, action):
        raise NotImplementedError

    def destroy(self):
        return None

    def getName(self):
        raise NotImplementedError

    def getDependence(self):
        return []

    def loadSpider(self, name):
        return self.loadModule(name).Spider()

    def loadModule(self, name):
        path = os.path.join(os.getcwd(), f"{name}.py")
        return SourceFileLoader(name, path).load_module()

    def regStr(self, reg, src, group=1):
        match = re.search(reg, src)
        return match.group(group) if match else ""

    def removeHtmlTags(self, src):
        return re.sub(re.compile("<.*?>"), "", src)

    def cleanText(self, src):
        return re.sub(
            "[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF]",
            "",
            src,
        )

    def fetch(
            self,
            url,
            params=None,
            cookies=None,
            headers=None,
            timeout=5,
            verify=True,
            stream=False,
            allow_redirects=True,
    ):
        rsp = requests.get(
            url,
            params=params,
            cookies=cookies,
            headers=headers,
            timeout=timeout,
            verify=verify,
            stream=stream,
            allow_redirects=allow_redirects,
        )
        rsp.encoding = "utf-8"
        return rsp

    def post(
            self,
            url,
            params=None,
            data=None,
            json=None,
            cookies=None,
            headers=None,
            timeout=5,
            verify=True,
            stream=False,
            allow_redirects=True,
    ):
        rsp = requests.post(
            url,
            params=params,
            data=data,
            json=json,
            cookies=cookies,
            headers=headers,
            timeout=timeout,
            verify=verify,
            stream=stream,
            allow_redirects=allow_redirects,
        )
        rsp.encoding = "utf-8"
        return rsp

    def html(self, content):
        return etree.HTML(content)

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
    PUSH_PREFIX = "push://"
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
        self._detail_result_cache = {}
        self._play_context_cache = {}
        self._filters = []

    def init(self, extend=""):
        self.extend = extend or ""
        payload = self._decode_ext_payload(self.extend)
        source, inner_extend = self._split_ext(self.extend)
        self._backend_api = self._resolve_backend_api(source, payload)
        self._vod_token = self._resolve_vod_token(payload)
        self._localProxyConfig = payload.get("local_proxy_config")
        package_text = self._load_source(source)
        source_text = self._decrypt_secspider_source(package_text)
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
        return "\n".join(
            [
                f"//@name:{headers['name']}",
                f"//@version:{headers['version']}",
                f"//@remark:{headers['remark']}",
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
        ).encode("utf-8")

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

    def _load_inner_spider_class(self, source_text):
        module = types.ModuleType("atvp_inner_spider")
        module.__file__ = "<atvp-inner>"
        exec(compile(source_text, module.__file__, "exec"), module.__dict__)
        spider_cls = getattr(module, "Spider", None)
        if spider_cls is None:
            raise ValueError("Atvp inner source does not export Spider")
        return spider_cls

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

    def _require_inner(self):
        if self._inner is None:
            raise RuntimeError("Atvp spider is not initialized")
        return self._inner

    def _decode_parse(self, vod_id):
        value = str(vod_id or "").strip()
        if (value.startswith("http://") or value.startswith("https://")
                or value.startswith("magnet:") or value.startswith("ed2k:")):
            return value
        return None

    def _encode_category_id(self, vod_id):
        return self.DETAIL_PREFIX + vod_id

    def _parse(self, share_url):
        api = self._build_backend_endpoint("parse")
        rsp = self.post(api, json={"url": share_url}, params={"ac": "play"}, timeout=10)
        if rsp.status_code != 200:
            return self.PUSH_PREFIX + share_url
        body = str(rsp.text or "")
        if rsp.status_code != 200 or not body.strip():
            raise ValueError(f"Atvp parse failed: {share_url}")
        parsed_result = json.loads(body)
        parsed_result = self._merge_cached_detail_result(share_url, parsed_result)
        parsed_result = self._run_filters("parse", parsed_result, {"share_url": share_url})
        parsed_result = self._run_filters("detail", parsed_result, {"share_url": share_url, "source": "parse"})
        self._cache_detail_result(parsed_result)
        self._cache_play_context(parsed_result)
        return parsed_result

    def _cache_detail_result(self, detail_result):
        vod_list = detail_result.get("list") if isinstance(detail_result, dict) else None
        if not isinstance(vod_list, list) or len(vod_list) != 1:
            return

        vod = vod_list[0]
        if not isinstance(vod, dict):
            return

        for url_group in str(vod.get("vod_play_url") or "").split("$$$"):
            _, _, target = str(url_group or "").partition("$")
            if target.startswith(self.PUSH_PREFIX):
                target = target[len(self.PUSH_PREFIX):]
            share_url = self._decode_parse(target)
            if share_url is None:
                continue
            self._detail_result_cache[share_url] = dict(vod)

    def _cache_play_context(self, detail_result):
        vod_list = detail_result.get("list") if isinstance(detail_result, dict) else None
        if not isinstance(vod_list, list):
            return

        total = 0
        for vod in vod_list:
            if not isinstance(vod, dict):
                continue
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
        rsp = self.fetch(
            self._build_backend_endpoint("play"),
            params={"id": str(play_id or "")},
            timeout=10,
        )
        body = str(rsp.text or "")
        if rsp.status_code != 200 or not body.strip():
            raise ValueError(f"Atvp play failed: {play_id}")
        # proxy = self.post("http://localhost:5000/player", json={"playerContent": body, "taskSeed": play_id, "localProxyConfig": self._localProxyConfig}, timeout=10)
        # if proxy.status_code == 200:
        #     return proxy.json()
        return self._run_filters("play", json.loads(body), self._build_player_context(play_id=play_id))

    def _split_detail_to_vods(self, source_id):
        detail_result = self._require_inner().detailContent([source_id])
        detail_result = self._run_filters("detail", detail_result, {"ids": [source_id], "source": "category"})
        self._cache_detail_result(detail_result)
        self._cache_play_context(detail_result)
        vod_list = detail_result.get("list") if isinstance(detail_result, dict) else None
        if not isinstance(vod_list, list) or len(vod_list) != 1:
            return None

        vod = vod_list[0]
        if not isinstance(vod, dict):
            return None

        play_from_value = str(vod.get("vod_play_from") or "")
        play_url_value = str(vod.get("vod_play_url") or "")
        if not play_from_value or not play_url_value:
            return None

        from_groups = play_from_value.split("$$$")
        url_groups = play_url_value.split("$$$")
        if len(from_groups) != len(url_groups):
            return None

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
        vod = {
            "list": items,
            "page": 1,
            "pagecount": 1,
            "limit": len(items),
            "total": len(items),
        }
        return vod

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
            item["vod_id"] = self._encode_category_id(item.get("vod_id", ""))
            item["vod_tag"] = "folder"
            normalized.append(item)

        payload = dict(result)
        payload["list"] = normalized
        return payload

    def homeContent(self, filter):
        return self._require_inner().homeContent(filter)

    def homeVideoContent(self):
        return self._require_inner().homeVideoContent()

    def categoryContent(self, tid, pg, filter, extend):
        print('categoryContent', tid, pg, filter, extend)
        if not self._category_mode_enabled():
            return self._require_inner().categoryContent(tid, pg, filter, extend)
        if tid.startswith(self.DETAIL_PREFIX):
            tid = tid[len(self.DETAIL_PREFIX):]
            return self._split_detail_to_vods(tid)
        result = self._require_inner().categoryContent(tid, pg, filter, extend)
        return self._normalize_category_content(result)

    def detailContent(self, ids):
        print('detailContent', ids)
        if isinstance(ids, (list, tuple)) and len(ids) == 1:
            share_url = self._decode_parse(ids[0])
            if share_url is not None:
                return self._parse(share_url)
        result = self._require_inner().detailContent(ids)
        result = self._run_filters("detail", result, {"ids": ids})
        self._cache_detail_result(result)
        self._cache_play_context(result)
        return result

    def searchContent(self, key, quick, pg="1"):
        print('searchContent', key, quick, pg)
        result = self._require_inner().searchContent(key, quick, int(pg))
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
        return self._run_filters("player", result, self._build_player_context(flag, id, vipFlags))

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
