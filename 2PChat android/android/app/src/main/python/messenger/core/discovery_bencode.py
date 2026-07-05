from __future__ import annotations


def bdecode(payload: bytes):
    payload = payload.strip()
    value, offset = _decode_at(payload, 0)
    if offset != len(payload):
        raise ValueError("Trailing data after bencoded value")
    return value


def _decode_at(payload: bytes, offset: int):
    if offset >= len(payload):
        raise ValueError("Unexpected end of bencoded payload")
    token = payload[offset : offset + 1]
    if token == b"i":
        end = payload.index(b"e", offset)
        return int(payload[offset + 1 : end]), end + 1
    if token == b"l":
        offset += 1
        items = []
        while payload[offset : offset + 1] != b"e":
            item, offset = _decode_at(payload, offset)
            items.append(item)
        return items, offset + 1
    if token == b"d":
        offset += 1
        data = {}
        while payload[offset : offset + 1] != b"e":
            key, offset = _decode_at(payload, offset)
            value, offset = _decode_at(payload, offset)
            if isinstance(key, bytes):
                key = key.decode("utf-8", errors="replace")
            data[key] = value
        return data, offset + 1
    if token.isdigit():
        colon = payload.index(b":", offset)
        length = int(payload[offset:colon])
        start = colon + 1
        end = start + length
        return payload[start:end], end
    raise ValueError("Unsupported bencode token")
