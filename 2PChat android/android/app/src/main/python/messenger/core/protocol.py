import json
from typing import Any, Dict

import cbor2


DEFAULT_FORMAT = "json"  # could be "cbor" later


def encode_message(message: Dict[str, Any], encoding: str = DEFAULT_FORMAT) -> bytes:
    if encoding == "json":
        return json.dumps(message).encode("utf-8")
    if encoding == "cbor":
        return cbor2.dumps(message)
    raise ValueError(f"Unsupported encoding: {encoding}")


def decode_message(payload: bytes, encoding: str = DEFAULT_FORMAT) -> Dict[str, Any]:
    if encoding == "json":
        return json.loads(payload.decode("utf-8"))
    if encoding == "cbor":
        return cbor2.loads(payload)
    raise ValueError(f"Unsupported encoding: {encoding}")
