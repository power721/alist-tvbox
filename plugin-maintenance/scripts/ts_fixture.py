"""Small MPEG-TS fixture with a single PAT, H.264 PMT entry and PES prefix."""


def mpeg2_crc32(data: bytes) -> int:
    value = 0xFFFFFFFF
    for item in data:
        value ^= item << 24
        for _ in range(8):
            value = ((value << 1) ^ (0x04C11DB7 if value & 0x80000000 else 0)) & 0xFFFFFFFF
    return value


def _section_with_crc(section: bytes) -> bytes:
    checksum = mpeg2_crc32(section).to_bytes(4, "big")
    complete = section + checksum
    assert mpeg2_crc32(complete) == 0
    return complete


def _packet(pid: int, payload: bytes, *, start: bool, counter: int = 0) -> bytes:
    if len(payload) > 184:
        raise ValueError("TS payload exceeds packet capacity")
    header = bytes((0x47, ((0x40 if start else 0) | (pid >> 8)), pid & 0xFF, 0x10 | counter))
    return header + payload + bytes((0xFF,)) * (184 - len(payload))


def _psi_packet(pid: int, section: bytes) -> bytes:
    return _packet(pid, b"\x00" + section, start=True)


def _pes_packet(pid: int, payload: bytes) -> bytes:
    if len(payload) > 173:
        raise ValueError("PES payload exceeds adaptation packet capacity")
    pes = b"\x00\x00\x01\xe0" + len(payload + b"\x80\x00\x00").to_bytes(2, "big")
    pes += b"\x80\x00\x00" + payload
    adaptation_length = 183 - len(pes)
    adaptation = bytes((adaptation_length, 0)) + b"\xff" * (adaptation_length - 1)
    header = bytes((0x47, 0x40 | (pid >> 8), pid & 0xFF, 0x30))
    return header + adaptation + pes


def sample_ts() -> bytes:
    """Return three 188-byte packets: CRC-valid PAT/PMT and an H.264 PES prefix."""
    pat = _section_with_crc(
        b"\x00\xb0\x0d\x00\x01\xc1\x00\x00\x00\x01\xe1\x00"
    )
    pmt = _section_with_crc(
        b"\x02\xb0\x12\x00\x01\xc1\x00\x00\xe1\x01\xf0\x00\x1b\xe1\x01\xf0\x00"
    )
    h264 = b"\x00\x00\x01\x65\x88"
    return b"".join(
        (
            _psi_packet(0, pat),
            _psi_packet(0x100, pmt),
            _pes_packet(0x101, h264),
        )
    )
