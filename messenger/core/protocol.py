import base64
import json
import struct
from typing import Any, Dict

import cbor2


DEFAULT_FORMAT = "json"  # could be "cbor" later
FILE_CHUNK_FRAME_TYPE = 0x02
FILE_ID_SIZE = 12
FILE_CHUNK_FORMAT = "binary-v1"
DEFAULT_FILE_CHUNK_SIZE = 256 * 1024
FILE_CHUNK_SECRETBOX_OVERHEAD = 40
MAX_FILE_CHUNK_PAYLOAD_SIZE = (
    DEFAULT_FILE_CHUNK_SIZE + FILE_CHUNK_SECRETBOX_OVERHEAD
)
_FILE_CHUNK_HEADER = struct.Struct(">B12sII")


def file_chunk_ack_id(file_id: bytes, chunk_index: int) -> str:
    """Return the reliable-message ID implied by a binary file chunk header."""

    if not isinstance(file_id, bytes):
        raise TypeError("file_id must be bytes")
    if len(file_id) != FILE_ID_SIZE:
        raise ValueError(f"file_id must be exactly {FILE_ID_SIZE} bytes")
    if not 0 <= chunk_index <= 0xFFFFFFFF:
        raise ValueError("chunk_index must fit in an unsigned 32-bit integer")
    encoded_id = base64.urlsafe_b64encode(file_id).decode("ascii").rstrip("=")
    return f"file:{encoded_id}:{chunk_index}"


def encode_file_chunk(file_id: bytes, chunk_index: int, payload: bytes) -> bytes:
    """Encode an encrypted file chunk without JSON or Base64 expansion."""

    file_chunk_ack_id(file_id, chunk_index)
    if not isinstance(payload, bytes):
        raise TypeError("file chunk payload must be bytes")
    if len(payload) > MAX_FILE_CHUNK_PAYLOAD_SIZE:
        raise ValueError(
            "file chunk payload exceeds the binary-v1 maximum of "
            f"{MAX_FILE_CHUNK_PAYLOAD_SIZE} bytes"
        )
    return _FILE_CHUNK_HEADER.pack(
        FILE_CHUNK_FRAME_TYPE,
        file_id,
        chunk_index,
        len(payload),
    ) + payload


def decode_file_chunk(payload: bytes) -> Dict[str, Any]:
    """Decode the binary file chunk format used inside an encrypted session."""

    if len(payload) < _FILE_CHUNK_HEADER.size:
        raise ValueError("binary file chunk is shorter than its header")
    frame_type, file_id, chunk_index, payload_size = _FILE_CHUNK_HEADER.unpack_from(payload)
    if frame_type != FILE_CHUNK_FRAME_TYPE:
        raise ValueError(f"unsupported binary frame type: {frame_type}")
    if payload_size > MAX_FILE_CHUNK_PAYLOAD_SIZE:
        raise ValueError(
            "file chunk payload exceeds the binary-v1 maximum of "
            f"{MAX_FILE_CHUNK_PAYLOAD_SIZE} bytes"
        )
    encrypted_chunk = payload[_FILE_CHUNK_HEADER.size :]
    if len(encrypted_chunk) != payload_size:
        raise ValueError(
            f"binary file chunk length mismatch: expected {payload_size}, "
            f"received {len(encrypted_chunk)}"
        )
    return {
        "type": "file_chunk",
        "id": file_chunk_ack_id(file_id, chunk_index),
        "file_id": base64.b64encode(file_id).decode("ascii"),
        "chunk_index": chunk_index,
        "payload": encrypted_chunk,
    }


def validate_file_metadata(message: Dict[str, Any]) -> None:
    """Require metadata which explicitly opts into the binary-only protocol."""

    if message.get("type") != "file_meta":
        raise ValueError("expected file_meta message")
    if message.get("chunk_format") != FILE_CHUNK_FORMAT:
        raise ValueError(f"file metadata must declare chunk_format={FILE_CHUNK_FORMAT}")
    chunk_size = message.get("chunk_size")
    if type(chunk_size) is not int or not 0 < chunk_size <= DEFAULT_FILE_CHUNK_SIZE:
        raise ValueError(
            f"file metadata chunk_size must be between 1 and {DEFAULT_FILE_CHUNK_SIZE}"
        )


def _validate_structured_message(message: Dict[str, Any]) -> Dict[str, Any]:
    if not isinstance(message, dict):
        raise ValueError("structured messages must decode to an object")
    if message.get("type") == "file_chunk":
        raise ValueError("structured file_chunk messages are no longer supported")
    if message.get("type") == "file_meta":
        validate_file_metadata(message)
    return message


def encode_message(message: Dict[str, Any], encoding: str = DEFAULT_FORMAT) -> bytes:
    _validate_structured_message(message)
    if encoding == "json":
        return json.dumps(message).encode("utf-8")
    if encoding == "cbor":
        return cbor2.dumps(message)
    raise ValueError(f"Unsupported encoding: {encoding}")


MAX_ALLOWED_PAYLOAD_SIZE = 10 * 1024 * 1024  # 10 MB limit


def decode_message(payload: bytes, encoding: str = DEFAULT_FORMAT) -> Dict[str, Any]:
    if not isinstance(payload, (bytes, bytearray)):
        raise TypeError("payload must be bytes")
    if len(payload) > MAX_ALLOWED_PAYLOAD_SIZE:
        raise ValueError(
            f"message payload size ({len(payload)} bytes) exceeds the maximum allowed limit of {MAX_ALLOWED_PAYLOAD_SIZE} bytes"
        )
    if payload and payload[0] == FILE_CHUNK_FRAME_TYPE:
        return decode_file_chunk(payload)
    if encoding == "json":
        return _validate_structured_message(json.loads(payload.decode("utf-8")))
    if encoding == "cbor":
        return _validate_structured_message(cbor2.loads(payload))
    raise ValueError(f"Unsupported encoding: {encoding}")
