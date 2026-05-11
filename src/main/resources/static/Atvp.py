# coding=utf-8
import base64
import html
import json
import os
import re
import types
from abc import ABCMeta, abstractmethod
from importlib.machinery import SourceFileLoader
from pathlib import Path
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


class Spider(HostSpider):
    PUBLIC_KEY_XOR = 23
    MASTER_SECRET_XOR = 41
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

    def init(self, extend=""):
        self.extend = extend or ""
        source, inner_extend = self._split_ext(self.extend)
        package_text = self._load_source(source)
        source_text = self._decrypt_secspider_source(package_text)
        spider_cls = self._load_inner_spider_class(source_text)
        self._inner = spider_cls()
        return self._inner.init(inner_extend)

    def getName(self):
        if self._inner is not None and hasattr(self._inner, "getName"):
            return self._inner.getName()
        return self.name

    def _split_ext(self, extend):
        raw = str(extend or "").strip()
        if "@@" not in raw:
            return raw, ""
        source, inner = raw.split("@@", 1)
        return source.strip(), inner

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

    def _require_inner(self):
        if self._inner is None:
            raise RuntimeError("Atvp spider is not initialized")
        return self._inner

    def homeContent(self, filter):
        return self._require_inner().homeContent(filter)

    def homeVideoContent(self):
        return self._require_inner().homeVideoContent()

    def categoryContent(self, tid, pg, filter, extend):
        return self._require_inner().categoryContent(tid, pg, filter, extend)

    def detailContent(self, ids):
        return self._require_inner().detailContent(ids)

    def searchContent(self, key, quick, pg="1"):
        return self._require_inner().searchContent(key, quick, pg)

    def _is_qqmusic_qrc_xml(self, text):
        value = str(text or "").strip()
        return value.startswith("<?xml") and "<QrcInfos" in value and "LyricContent=" in value

    def _qq_qrc_xml_to_apk_lrc(self, xml_text):
        value = str(xml_text or "").strip()
        if not value:
            return ""
        raw = ""
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
        result = self._require_inner().playerContent(flag, id, vipFlags)
        return self._normalize_player_content(result)

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
        if hasattr(inner, "danmaku"):
            return inner.danmaku()
        return False

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
