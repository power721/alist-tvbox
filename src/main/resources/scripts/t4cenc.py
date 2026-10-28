"""红果短剧 CENC/AES-CTR MP4 流式解密代理。

上游返回的 MP4 是整段 CENC 加密（cenc-aes-ctr，全样本加密），密钥由
video_model 的 encrypt_info.spade_a 派生。本模块解析 moov 样本表，
按 Range 请求边拉边解，moov 区域做 fourcc 还原（encv/enca/sinf）。
"""

from __future__ import annotations

import base64
import binascii
import json
import struct
from dataclasses import dataclass, field
from typing import Callable

from Crypto.Cipher import AES

CENC_FRAGMENT_KEY = "hg-cenc"


def derive_content_key(spade_a: str) -> bytes:
    """spade_a → CENC content key（红果官方派生算法）。"""
    raw = base64.b64decode(spade_a + "=" * (-len(spade_a) % 4))
    if len(raw) < 33:
        raise ValueError("spade_a 密钥材料无效")
    v8 = len(raw) - (raw[0] ^ raw[1] ^ raw[2]) + 47
    if v8 <= 0 or 1 + v8 > len(raw):
        v8 = len(raw) - 1
    if v8 < 33:
        raise ValueError("spade_a 长度异常")
    value = bytearray(raw[1 : 1 + v8])
    va, vb = 85, 246
    for index in range(v8):
        previous = va if index & 1 else vb
        if index & 1:
            va = value[index]
        else:
            vb = value[index]
        value[index] = (-21 - index.bit_count() + (previous ^ value[index])) & 0xFF
    try:
        key = binascii.unhexlify(bytes(value[1:33]).decode("ascii"))
    except (ValueError, binascii.Error) as exc:
        raise ValueError("spade_a 密钥材料无效") from exc
    if len(key) != 16:
        raise ValueError("CENC 密钥长度错误")
    return key


def build_cenc_media_fragment(
    spade_a: str,
    audio_url: str = "",
    audio_spade_a: str = "",
) -> str:
    payload: dict[str, str] = {"spade_a": spade_a}
    if audio_url:
        payload["audio"] = audio_url
        if audio_spade_a:
            payload["audio_spade_a"] = audio_spade_a
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":")).encode("utf-8")
    ).decode("ascii")
    return f"#{CENC_FRAGMENT_KEY}={encoded}"


def _decode_cenc_fragment(url: str) -> tuple[str, dict] | None:
    """拆出 (纯净媒体 URL, 片段 payload);无片段或 payload 非法返回 None。"""
    marker = f"#{CENC_FRAGMENT_KEY}="
    if marker not in url:
        return None
    clean_url, _separator, encoded = url.partition(marker)
    try:
        payload = json.loads(
            base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode("utf-8")
        )
    except (ValueError, UnicodeDecodeError):
        return None
    if not isinstance(payload, dict):
        return None
    return clean_url.strip(), payload


def parse_cenc_media_fragment(url: str) -> tuple[str, str] | None:
    """从播放地址里拆出 CENC 参数，返回 (纯净媒体 URL, spade_a)。"""
    decoded = _decode_cenc_fragment(url)
    if decoded is None:
        return None
    clean_url, payload = decoded
    spade_a = str(payload.get("spade_a") or "").strip()
    if not spade_a:
        return None
    return clean_url, spade_a


def parse_cenc_audio_fragment(url: str) -> tuple[str, str] | None:
    """拆出片段携带的分离音频流(红果电影 DASH 式音视频分离)。

    返回 (音频 URL, 音频 spade_a);音频未单独给密钥时回退视频的 spade_a,
    无音频字段返回 None。
    """
    decoded = _decode_cenc_fragment(url)
    if decoded is None:
        return None
    _clean_url, payload = decoded
    audio_url = str(payload.get("audio") or "").strip()
    if not audio_url.startswith(("http://", "https://")):
        return None
    audio_spade_a = str(payload.get("audio_spade_a") or "").strip() or str(
        payload.get("spade_a") or ""
    ).strip()
    return audio_url, audio_spade_a


@dataclass
class _CencSample:
    offset: int
    size: int
    iv: bytes
    # fMP4 子样本加密时的 (clear, cipher) 链;None = 整样本加密。
    subsamples: list[tuple[int, int]] | None = None


@dataclass
class CencMediaIndex:
    total_size: int
    prefix: bytes = b""
    prefix_end: int = 0
    samples: list[_CencSample] = field(default_factory=list)
    # fMP4(电影逐片段 CENC):sidx 片段表 (moof 绝对偏移, 片段大小)。
    fragments: list[tuple[int, int]] = field(default_factory=list)


def _find_box(
    data: memoryview | bytes,
    fourcc: bytes,
    start: int,
    *,
    size_limit: int | None = None,
) -> tuple[int, int]:
    # size_limit 允许命中"起点在窗口内、box 超出窗口"的情况（大 moov 探测）。
    fit_limit = len(data) if size_limit is None else size_limit
    for index in range(max(4, start), len(data) - 4):
        if data[index : index + 4] != fourcc:
            continue
        size = struct.unpack_from(">I", data, index - 4)[0]
        if size == 1 and index + 12 <= len(data):
            size = struct.unpack_from(">Q", data, index + 4)[0]
        if 8 <= size <= 500_000_000 and index - 4 + size <= fit_limit:
            return index - 4, size
    return -1, 0


def _box_body(data: memoryview, fourcc: bytes, start: int) -> memoryview | None:
    offset, size = _find_box(data, fourcc, start)
    return data[offset + 8 : offset + size] if offset >= 0 else None


def _parse_track_tables(moov: memoryview, track_offset: int) -> dict | None:
    """解析单个 trak 的样本表；返回样本尺寸/块偏移与 saiz/saio 信息。"""
    if track_offset < 0:
        return None
    stbl_offset, _ = _find_box(moov, b"stbl", track_offset + 8)
    if stbl_offset < 0:
        return None
    stsz = _box_body(moov, b"stsz", stbl_offset)
    stco = _box_body(moov, b"stco", stbl_offset)
    co64 = _box_body(moov, b"co64", stbl_offset)
    stsc = _box_body(moov, b"stsc", stbl_offset)
    saiz = _box_body(moov, b"saiz", stbl_offset)
    saio = _box_body(moov, b"saio", stbl_offset)
    if any(value is None for value in (stsz, stsc, saiz, saio)) or (
        stco is None and co64 is None
    ):
        return None
    default_size = struct.unpack_from(">I", stsz, 4)[0]
    sample_count = struct.unpack_from(">I", stsz, 8)[0]
    sizes = (
        [default_size] * sample_count
        if default_size
        else [
            struct.unpack_from(">I", stsz, 12 + index * 4)[0]
            for index in range(sample_count)
        ]
    )
    chunk_table = stco if stco is not None else co64
    chunk_count = struct.unpack_from(">I", chunk_table, 4)[0]
    chunk_width = 4 if stco is not None else 8
    offsets = [
        int.from_bytes(
            chunk_table[8 + index * chunk_width : 8 + (index + 1) * chunk_width],
            "big",
        )
        for index in range(chunk_count)
    ]
    entry_count = struct.unpack_from(">I", stsc, 4)[0]
    entries = [
        (
            struct.unpack_from(">I", stsc, 8 + index * 12)[0],
            struct.unpack_from(">I", stsc, 12 + index * 12)[0],
        )
        for index in range(entry_count)
    ]
    chunk_samples = [0] * chunk_count
    for index, (first_chunk, samples_per_chunk) in enumerate(entries):
        end = entries[index + 1][0] - 1 if index + 1 < len(entries) else chunk_count
        for chunk in range(first_chunk - 1, min(end, chunk_count)):
            chunk_samples[chunk] = samples_per_chunk
    saiz_flags = int.from_bytes(saiz[1:4], "big")
    saiz_cursor = 12 if saiz_flags & 1 else 4
    if len(saiz) < saiz_cursor + 5:
        return None
    default_aux_size = saiz[saiz_cursor]
    aux_count = struct.unpack_from(">I", saiz, saiz_cursor + 1)[0]
    aux_sizes = (
        [default_aux_size] * aux_count
        if default_aux_size
        else [
            int(saiz[saiz_cursor + 5 + index])
            for index in range(aux_count)
            if saiz_cursor + 5 + index < len(saiz)
        ]
    )
    if len(aux_sizes) != aux_count:
        return None
    saio_flags = int.from_bytes(saio[1:4], "big")
    saio_cursor = 12 if saio_flags & 1 else 4
    offset_width = 8 if saio[0] == 1 else 4
    if len(saio) < saio_cursor + 4 + offset_width:
        return None
    saio_entry_count = int.from_bytes(saio[saio_cursor : saio_cursor + 4], "big")
    if saio_entry_count < 1:
        return None
    aux_offset = int.from_bytes(saio[saio_cursor + 4 : saio_cursor + 4 + offset_width], "big")
    return {
        "sizes": sizes,
        "offsets": offsets,
        "chunk_samples": chunk_samples,
        "aux_sizes": aux_sizes,
        "aux_offset": aux_offset,
    }


def _parse_tenc_iv_size(moov_bytes: bytes) -> int:
    """moov 内 tenc 声明的逐样本 IV 长度(8/16);未加密或不认识返回 0。"""
    data = bytes(moov_bytes)
    position = 0
    while True:
        position = data.find(b"tenc", position)
        if position < 4:
            return 0
        box_size = struct.unpack_from(">I", data, position - 4)[0]
        body = data[position + 4 : position - 4 + box_size]
        # FullBox(4) + [reserved(1) + reserved/byte_block(1)] + is_protected(1) + iv_size(1)
        if len(body) >= 10 and body[6] == 1 and body[7] in (8, 16):
            return body[7]
        position += 4


def _parse_sidx_fragments(head: bytes, sidx_offset: int, sidx_size: int) -> list[tuple[int, int]]:
    """sidx → [(片段绝对偏移, 片段大小)];布局异常/含子 sidx 引用返回空表。"""
    body = head[sidx_offset + 8 : sidx_offset + sidx_size]
    if len(body) < 24:
        return []
    version = body[0]
    if version == 0:
        first_offset = int.from_bytes(body[16:20], "big")
        count_at, entries_at = 22, 24
    elif version == 1:
        if len(body) < 32:
            return []
        first_offset = int.from_bytes(body[20:28], "big")
        count_at, entries_at = 30, 32
    else:
        return []
    count = struct.unpack_from(">H", body, count_at)[0]
    if entries_at + count * 12 > len(body):
        return []
    start = sidx_offset + sidx_size + first_offset
    fragments: list[tuple[int, int]] = []
    for index in range(count):
        word = struct.unpack_from(">I", body, entries_at + index * 12)[0]
        reference_size = word & 0x7FFFFFFF
        if word >> 31 or reference_size < 8:
            # reference_type=1 指向子 sidx,电影流不出现;防脏数据直接放弃。
            return []
        fragments.append((start, reference_size))
        start += reference_size
    return fragments


def _patch_moov_prefix(data: bytes) -> bytes:
    """还原 ftyp+moov 区域的 fourcc：encv/enca→原格式（frma），sinf→free。"""
    result = bytearray(data)
    replacements: list[tuple[int, bytes]] = []
    position = 0
    while True:
        position = result.find(b"sinf", position)
        if position < 0:
            break
        if position >= 4:
            box_size = struct.unpack_from(">I", result, position - 4)[0]
            box = result[position - 4 : position - 4 + box_size]
            frma = box.find(b"frma")
            original = bytes(box[frma + 4 : frma + 8]) if frma >= 0 else b""
            owner = bytes(result[position - 12 : position - 8]) if position >= 12 else b""
            if owner == b"encv" and len(original) == 4:
                replacements.append((position - 12, original))
            elif owner == b"enca" and len(original) == 4:
                replacements.append((position - 12, original))
        position += 4
    for offset, original in replacements:
        result[offset : offset + 4] = original

    def replace_fourcc(old: bytes, new: bytes) -> None:
        cursor = 0
        while True:
            cursor = result.find(old, cursor)
            if cursor < 0:
                return
            result[cursor : cursor + 4] = new
            cursor += 4

    replace_fourcc(b"encv", b"hvc1")
    replace_fourcc(b"enca", b"mp4a")
    position = 0
    while True:
        position = result.find(b"sinf", position)
        if position < 0:
            break
        if position >= 4:
            size = struct.unpack_from(">I", result, position - 4)[0]
            end = position - 4 + size
            if 8 <= size < 100_000 and end <= len(result):
                result[position : position + 4] = b"free"
                result[position + 4 : end] = b"\x00" * max(0, end - position - 4)
                position = end
                continue
        position += 4
    return bytes(result)


class CencRangeReader:
    """按需解析样本表并对任意 Range 返回解密后的明文字节。"""

    def __init__(
        self,
        url: str,
        headers: dict[str, str],
        spade_a: str,
        *,
        get: Callable[..., object],
        probe_bytes: int = 512 * 1024,
    ) -> None:
        self._url = url
        self._headers = dict(headers)
        self._key = derive_content_key(spade_a)
        self._get = get
        self._probe_bytes = probe_bytes
        self._index: CencMediaIndex | None = None
        # fMP4(电影逐片段 CENC)状态:tenc 声明的逐样本 IV 长度 + 片段样本缓存。
        self._per_sample_iv_size = 0
        # moof 偏移 -> (样本表, CENC 盒类型字段偏移, seig 组类型字段偏移)。
        self._fragment_cache: dict[
            int, tuple[list[_CencSample], list[int], list[int]]
        ] = {}

    def _fetch_range(self, start: int, end: int) -> bytes:
        # 上游偶发短响应(负载/边缘节点),必须补拉到请求长度,
        # 否则 read_range 会出现字节缺口,播放器 demuxer 拿到错位流会卡死。
        want = end - start + 1
        cursor = start
        parts: list[bytes] = []
        for _attempt in range(4):
            response = self._get(
                self._url,
                headers={**self._headers, "Range": f"bytes={cursor}-{end}"},
                timeout=20.0,
                follow_redirects=True,
            )
            status = int(getattr(response, "status_code", 0) or 0)
            if status not in (200, 206):
                raise ValueError(f"CENC 媒体源返回 {status}")
            content = getattr(response, "content", b"")
            if isinstance(content, str):
                content = content.encode("utf-8", errors="replace")
            if status == 200 and cursor > 0:
                # 镜像忽略 Range 回了整文件:从全文里切出请求窗口。
                content = content[cursor : end + 1]
            else:
                headers_obj = getattr(response, "headers", None)
                content_range = ""
                if headers_obj is not None:
                    try:
                        content_range = str(headers_obj.get("Content-Range") or "")
                    except Exception:
                        content_range = ""
                if content_range.startswith("bytes ") and "-" in content_range:
                    try:
                        actual_start = int(content_range[6:].split("-", 1)[0])
                    except ValueError:
                        actual_start = cursor
                    if actual_start != cursor:
                        # 起点错位的响应直接丢弃重试。
                        continue
            parts.append(content)
            cursor += len(content)
            if cursor > end or not content:
                break
        data = b"".join(parts)
        if len(data) < want:
            raise ValueError(
                f"CENC 媒体源短响应: {len(data)}/{want} bytes @{start}"
            )
        return data[:want]

    def _probe_total_size(self) -> int:
        response = self._get(
            self._url,
            headers={**self._headers, "Range": "bytes=0-0"},
            timeout=20.0,
            follow_redirects=True,
        )
        headers_obj = getattr(response, "headers", None)
        if headers_obj is not None:
            try:
                content_range = str(headers_obj.get("Content-Range") or "")
            except Exception:
                content_range = ""
            if "/" in content_range:
                try:
                    return int(content_range.rsplit("/", 1)[1])
                except ValueError:
                    pass
        return len(getattr(response, "content", b"") or b"")

    def ensure_index(self) -> CencMediaIndex:
        if self._index is not None:
            return self._index
        total_size = self._probe_total_size()
        head = self._fetch_range(0, min(self._probe_bytes, total_size - 1))
        # size_limit=total_size：长内容的 moov（大样本表）可远超探测窗口，
        # 只要起点在窗口内且整盒不越出文件就算命中，随后补拉缺失部分。
        moov_start, moov_size = _find_box(
            memoryview(head), b"moov", 0, size_limit=total_size
        )
        if moov_start < 0:
            # moov 在文件尾部的情况：尾部探测。
            tail_start = max(0, total_size - self._probe_bytes)
            tail = self._fetch_range(tail_start, total_size - 1)
            moov_start, moov_size = _find_box(memoryview(tail), b"moov", 0)
            if moov_start < 0:
                raise ValueError("CENC 媒体没有可解析的 moov")
            moov_abs_start = tail_start + moov_start
            moov_bytes = self._fetch_range(moov_abs_start, moov_abs_start + moov_size - 1)
            prefix_bytes = self._fetch_range(0, moov_abs_start - 1) if moov_abs_start > 0 else b""
        else:
            if moov_start + moov_size > len(head):
                moov_bytes = self._fetch_range(moov_start, moov_start + moov_size - 1)
            else:
                moov_bytes = head[moov_start : moov_start + moov_size]
            prefix_bytes = head[:moov_start]
        self._index = self._build_index(
            moov_bytes, prefix_bytes, total_size, head_bytes=head
        )
        return self._index

    def _build_index(
        self,
        moov_bytes: bytes,
        prefix_bytes: bytes,
        total_size: int,
        head_bytes: bytes = b"",
    ) -> CencMediaIndex:
        moov = memoryview(moov_bytes)
        samples: list[_CencSample] = []
        search = 0
        while True:
            track, track_size = _find_box(moov, b"trak", search)
            if track < 0:
                break
            tables = _parse_track_tables(moov, track)
            if tables is not None:
                samples.extend(self._build_track_samples(tables))
            search = track + max(track_size, 8)
        samples.sort(key=lambda sample: sample.offset)
        fragments: list[tuple[int, int]] = []
        if not samples:
            # fMP4(电影逐片段 CENC):整段样本表为空时按 sidx 片段表懒解密。
            # 实测电影流开头若干片段为明文(无 senc),其余片段整样本 AES-CTR,
            # IV 逐样本存于 traf/senc(tenc 声明 8/16 字节)。
            self._per_sample_iv_size = _parse_tenc_iv_size(moov_bytes)
            if self._per_sample_iv_size and head_bytes:
                sidx_offset, sidx_size = _find_box(memoryview(head_bytes), b"sidx", 0)
                if sidx_offset >= 0:
                    fragments = _parse_sidx_fragments(head_bytes, sidx_offset, sidx_size)
        prefix = _patch_moov_prefix(prefix_bytes + moov_bytes)
        return CencMediaIndex(
            total_size=total_size,
            prefix=prefix,
            prefix_end=len(prefix),
            samples=samples,
            fragments=fragments,
        )

    def _build_track_samples(self, tables: dict) -> list[_CencSample]:
        sizes = tables["sizes"]
        offsets = tables["offsets"]
        chunk_samples = tables["chunk_samples"]
        aux_sizes = tables["aux_sizes"]
        aux_offset = tables["aux_offset"]
        # saio 指向文件内（mdat 区）逐样本 IV 表，按需整体拉取。
        aux_total = sum(max(size, 8) for size in aux_sizes)
        aux_bytes = self._fetch_range(aux_offset, aux_offset + aux_total - 1)
        samples: list[_CencSample] = []
        sample_index = 0
        aux_cursor = 0
        for chunk_offset, per_chunk in zip(offsets, chunk_samples):
            current = chunk_offset
            for _ in range(per_chunk):
                if sample_index >= len(sizes) or sample_index >= len(aux_sizes):
                    break
                iv = aux_bytes[aux_cursor : aux_cursor + max(aux_sizes[sample_index], 8)][:16]
                samples.append(
                    _CencSample(
                        offset=current,
                        size=sizes[sample_index],
                        iv=iv.ljust(16, b"\0"),
                    )
                )
                aux_cursor += max(aux_sizes[sample_index], 8)
                current += sizes[sample_index]
                sample_index += 1
        return samples

    def read_range(self, start: int, inclusive_end: int) -> bytes:
        index = self.ensure_index()
        inclusive_end = min(inclusive_end, index.total_size - 1)
        if start < 0 or inclusive_end < start:
            return b""
        output = bytearray()
        cursor = start
        while cursor <= inclusive_end:
            if cursor < index.prefix_end:
                window_end = min(inclusive_end, index.prefix_end - 1)
                output.extend(index.prefix[cursor : window_end + 1])
                cursor = window_end + 1
                continue
            window_end = min(inclusive_end, cursor + 1024 * 1024 - 1, index.total_size - 1)
            chunk = bytearray(self._fetch_range(cursor, window_end))
            if index.samples:
                for sample in index.samples:
                    if sample.offset > window_end:
                        break
                    self._xor_sample_in_window(chunk, cursor, window_end, sample)
            for moof_abs, fragment_size in index.fragments:
                if moof_abs > window_end:
                    break
                if moof_abs + fragment_size - 1 < cursor:
                    continue
                samples, box_type_offsets, seig_type_offsets = self._fragment_samples(
                    moof_abs, fragment_size
                )
                for sample in samples:
                    self._xor_sample_in_window(chunk, cursor, window_end, sample)
                self._neutralize_cenc_boxes(
                    chunk, cursor, moof_abs, box_type_offsets, seig_type_offsets
                )
            output.extend(chunk)
            cursor = window_end + 1
        return bytes(output)

    def _xor_sample_in_window(
        self, chunk: bytearray, cursor: int, window_end: int, sample: _CencSample
    ) -> None:
        sample_end = sample.offset + sample.size - 1
        if sample_end < cursor or sample.offset > window_end:
            return
        overlap_start = max(cursor, sample.offset)
        overlap_end = min(window_end, sample_end)
        if overlap_end < overlap_start:
            return
        if sample.subsamples is None:
            keystream = self._keystream(sample.iv, sample.size)
            self._xor_region(
                chunk,
                cursor,
                overlap_start,
                overlap_end,
                keystream,
                overlap_start - sample.offset,
            )
            return
        # 子样本加密:密钥流只按密文字节推进,每个子样本结束后对齐 16 字节块。
        stream_pos = 0
        position = sample.offset
        for clear, cipher in sample.subsamples:
            cipher_start = position + clear
            cipher_end = cipher_start + cipher - 1
            inter_start = max(overlap_start, cipher_start)
            inter_end = min(overlap_end, cipher_end)
            if inter_end >= inter_start and cipher:
                # 密钥流必须覆盖到本次重叠区间在子样本内的末端:
                # 长度不足时 zip 截短会让切片赋值缩短 chunk,造成字节丢失错位。
                key_offset = stream_pos + (inter_start - cipher_start)
                keystream = self._keystream(
                    sample.iv, key_offset + (inter_end - inter_start + 1)
                )
                self._xor_region(
                    chunk, cursor, inter_start, inter_end, keystream, key_offset
                )
            position = cipher_start + cipher
            stream_pos = (stream_pos + cipher + 15) // 16 * 16

    def _xor_region(
        self,
        chunk: bytearray,
        cursor: int,
        overlap_start: int,
        overlap_end: int,
        keystream: bytes,
        key_offset: int,
    ) -> None:
        local_start = overlap_start - cursor
        local_end = overlap_end - cursor
        span = local_end - local_start + 1
        key_slice = keystream[key_offset : key_offset + span]
        if len(key_slice) != span:
            # 切片赋值遇短字节串会缩短 chunk,造成字节流错位,必须显式失败。
            raise ValueError("CENC 密钥流长度不足")
        chunk[local_start : local_end + 1] = bytes(
            value ^ key
            for value, key in zip(chunk[local_start : local_end + 1], key_slice)
        )

    def _fragment_samples(
        self, moof_abs: int, fragment_size: int
    ) -> tuple[list[_CencSample], list[int], list[int]]:
        cached = self._fragment_cache.get(moof_abs)
        if cached is not None:
            return cached
        fetch_size = min(max(fragment_size, 8), 65_536)
        moof = self._fetch_range(moof_abs, moof_abs + fetch_size - 1)
        box_size = struct.unpack_from(">I", moof, 0)[0] if len(moof) >= 8 else 0
        samples: list[_CencSample] = []
        # CENC 辅助盒(seiz/saio/senc)类型字段与 seig 样本组 grouping_type 字段
        # 在 moof 内的偏移,输出前等长改写中和(见 _neutralize_cenc_boxes)。
        box_type_offsets: list[int] = []
        seig_type_offsets: list[int] = []
        if 8 <= box_size <= 5_000_000:
            if box_size > len(moof):
                moof = self._fetch_range(moof_abs, moof_abs + box_size - 1)
            fragment_end = moof_abs + max(fragment_size, box_size) - 1
            offset = 8
            while offset + 8 <= min(len(moof), box_size):
                traf_size = struct.unpack_from(">I", moof, offset)[0]
                traf_fourcc = moof[offset + 4 : offset + 8]
                if traf_size < 8 or offset + traf_size > len(moof):
                    break
                if traf_fourcc == b"traf":
                    samples.extend(
                        self._parse_traf_samples(
                            moof[offset : offset + traf_size],
                            moof_abs,
                            fragment_end,
                            offset,
                            box_type_offsets,
                            seig_type_offsets,
                        )
                    )
                offset += traf_size
        info = (samples, box_type_offsets, seig_type_offsets)
        if len(self._fragment_cache) > 256:
            self._fragment_cache.pop(next(iter(self._fragment_cache)))
        self._fragment_cache[moof_abs] = info
        return info

    @staticmethod
    def _neutralize_cenc_boxes(
        chunk: bytearray,
        cursor: int,
        moof_abs: int,
        box_type_offsets: list[int],
        seig_type_offsets: list[int],
    ) -> None:
        """输出已解密且 moov 已剥 sinf/tenc,traf 内残留的 saiz/saio/senc 会令严格
        解析器(ExoPlayer FragmentedMp4Extractor.parseTraf)在 encryptionBox 非空
        断言上崩溃,seig 样本组则会重建逐样本加密标记;两者等长改写让明文输出自洽
        (盒尺寸不变,trun data_offset/saio 偏移等全部继续成立)。ffmpeg 对这些盒
        本就不敏感。跨 chunk 的字段留给覆盖它的后续窗口补写。
        """
        for offsets, replacement in ((box_type_offsets, b"free"), (seig_type_offsets, b"seix")):
            for relative in offsets:
                absolute = moof_abs + relative
                if cursor <= absolute and absolute + 4 <= cursor + len(chunk):
                    chunk[absolute - cursor : absolute - cursor + 4] = replacement

    def _parse_traf_samples(
        self,
        traf: bytes,
        moof_abs: int,
        fragment_end: int,
        traf_origin: int,
        box_type_offsets: list[int],
        seig_type_offsets: list[int],
    ) -> list[_CencSample]:
        sizes: list[int] = []
        data_offset: int | None = None
        iv_entries: list[tuple[bytes, list[tuple[int, int]] | None]] = []
        offset = 8
        while offset + 8 <= len(traf):
            size = struct.unpack_from(">I", traf, offset)[0]
            fourcc = traf[offset + 4 : offset + 8]
            if size < 8 or offset + size > len(traf):
                break
            if fourcc in (b"saiz", b"saio", b"senc"):
                box_type_offsets.append(traf_origin + offset + 4)
            elif (
                fourcc in (b"sbgp", b"sgpd")
                and size >= 16
                and traf[offset + 12 : offset + 16] == b"seig"
            ):
                seig_type_offsets.append(traf_origin + offset + 12)
            body = traf[offset + 8 : offset + size]
            if fourcc == b"trun":
                flags = int.from_bytes(body[1:4], "big")
                count = struct.unpack_from(">I", body, 4)[0]
                cursor = 8
                if flags & 1:  # data_offset
                    if len(body) < cursor + 4:
                        break
                    data_offset = struct.unpack_from(">i", body, cursor)[0]
                    cursor += 4
                if flags & 4:  # first_sample_flags(固定头部字段,先于样本条目)
                    cursor += 4
                chunk_sizes: list[int] = []
                for _ in range(count):
                    step = (
                        (4 if flags & 0x100 else 0)
                        + (4 if flags & 0x200 else 0)
                        + (4 if flags & 0x400 else 0)
                        + (4 if flags & 0x800 else 0)
                    )
                    if cursor + step > len(body):
                        chunk_sizes = []
                        break
                    value = 0
                    if flags & 0x200:
                        value = struct.unpack_from(">I", body, cursor)[0]
                    chunk_sizes.append(value)
                    cursor += step
                if chunk_sizes:
                    sizes = chunk_sizes
            elif fourcc == b"senc":
                iv_entries = self._parse_senc_entries(body)
            offset += size
        if data_offset is None or not iv_entries or not sizes:
            # 无 senc 的片段是明文(电影开头试用段),透传不解密。
            return []
        samples: list[_CencSample] = []
        position = moof_abs + data_offset
        for size, (iv, subsamples) in zip(sizes, iv_entries):
            if size and iv and position + size - 1 <= fragment_end:
                samples.append(
                    _CencSample(
                        offset=position, size=size, iv=iv, subsamples=subsamples
                    )
                )
            position += size
        return samples

    def _parse_senc_entries(
        self, body: bytes
    ) -> list[tuple[bytes, list[tuple[int, int]] | None]]:
        if len(body) < 8:
            return []
        flags = int.from_bytes(body[1:4], "big")
        count = struct.unpack_from(">I", body, 4)[0]
        iv_size = self._per_sample_iv_size or 8
        entries: list[tuple[bytes, list[tuple[int, int]] | None]] = []
        cursor = 8
        # 红果电影的封装器用 flags=0x2 表示子样本加密(标准写法是 0x1),
        # 两种都认;flags=0 的音频流是整样本加密。
        has_subsamples = bool(flags & 0x3)
        for _ in range(count):
            if cursor + iv_size > len(body):
                return []
            iv = body[cursor : cursor + iv_size]
            cursor += iv_size
            subsamples: list[tuple[int, int]] | None = None
            if has_subsamples:
                if cursor + 2 > len(body):
                    return []
                pair_count = struct.unpack_from(">H", body, cursor)[0]
                cursor += 2
                pairs: list[tuple[int, int]] = []
                for _ in range(pair_count):
                    if cursor + 6 > len(body):
                        return []
                    clear, cipher = struct.unpack_from(">HI", body, cursor)
                    cursor += 6
                    pairs.append((clear, cipher))
                subsamples = pairs
            entries.append((iv, subsamples))
        return entries

    def _keystream(self, iv: bytes, length: int) -> bytes:
        cipher = AES.new(
            self._key,
            AES.MODE_CTR,
            nonce=b"",
            initial_value=int.from_bytes(iv[:16].ljust(16, b"\0"), "big"),
        )
        return cipher.encrypt(b"\0" * length)
