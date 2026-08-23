import base64
import json
import struct
import time

import pytest

from messenger.core import protocol


@pytest.mark.parametrize("encoding", ["json", "cbor"])
def test_protocol_round_trip(encoding):
    message = {"type": "chat", "timestamp": int(time.time()), "body": "hi"}
    encoded = protocol.encode_message(message, encoding=encoding)
    decoded = protocol.decode_message(encoded, encoding=encoding)
    assert decoded == message


def test_protocol_rejects_unknown_format():
    with pytest.raises(ValueError):
        protocol.encode_message({"type": "chat"}, encoding="unknown")


def test_binary_file_chunk_round_trip_has_fixed_21_byte_header():
    file_id = bytes.fromhex("00112233445566778899aabb")
    encrypted_chunk = b"\x00\xffbinary payload"

    encoded = protocol.encode_file_chunk(file_id, 15, encrypted_chunk)

    assert encoded[:1] == b"\x03"
    assert encoded[1:13] == file_id
    assert struct.unpack(">I", encoded[13:17])[0] == 15
    assert struct.unpack(">I", encoded[17:21])[0] == len(encrypted_chunk)
    assert len(encoded) == len(encrypted_chunk) + 21
    assert protocol.decode_message(encoded) == {
        "type": "file_chunk",
        "id": protocol.file_chunk_ack_id(file_id, 15),
        "file_id": base64.b64encode(file_id).decode("ascii"),
        "chunk_index": 15,
        "payload": encrypted_chunk,
        "chunk_format": "binary-v2",
    }


def test_legacy_binary_v1_file_chunk_remains_receive_compatible():
    encoded = bytearray(protocol.encode_file_chunk(b"abcdefghijkl", 2, b"legacy"))
    encoded[0] = protocol.LEGACY_FILE_CHUNK_FRAME_TYPE

    decoded = protocol.decode_message(bytes(encoded))

    assert decoded["chunk_format"] == "binary-v1"
    assert decoded["chunk_index"] == 2
    assert decoded["payload"] == b"legacy"


def test_binary_file_chunk_rejects_wrong_declared_size():
    encoded = bytearray(protocol.encode_file_chunk(b"x" * 12, 0, b"payload"))
    encoded[17:21] = (100).to_bytes(4, "big")

    with pytest.raises(ValueError, match="length mismatch"):
        protocol.decode_message(bytes(encoded))


@pytest.mark.parametrize("encoding", ["json", "cbor"])
def test_structured_file_chunk_cannot_be_encoded(encoding):
    with pytest.raises(ValueError, match="no longer supported"):
        protocol.encode_message(
            {
                "type": "file_chunk",
                "file_id": base64.b64encode(b"x" * 12).decode(),
                "chunk_index": 0,
                "payload": base64.b64encode(b"legacy").decode(),
            },
            encoding=encoding,
        )


def test_structured_file_chunk_cannot_be_decoded():
    legacy = json.dumps(
        {
            "type": "file_chunk",
            "file_id": base64.b64encode(b"x" * 12).decode(),
            "chunk_index": 0,
            "payload": base64.b64encode(b"legacy").decode(),
        }
    ).encode()

    with pytest.raises(ValueError, match="no longer supported"):
        protocol.decode_message(legacy)


def test_binary_file_metadata_is_required():
    with pytest.raises(ValueError, match="chunk_format=binary-v2"):
        protocol.validate_file_metadata(
            {
                "type": "file_meta",
                "chunk_size": protocol.DEFAULT_FILE_CHUNK_SIZE,
            }
        )

    with pytest.raises(ValueError, match="chunk_format=binary-v2"):
        protocol.decode_message(
            json.dumps(
                {
                    "type": "file_meta",
                    "chunk_size": protocol.DEFAULT_FILE_CHUNK_SIZE,
                }
            ).encode()
        )


def test_binary_file_chunk_rejects_payload_larger_than_256_kib():
    oversized = b"x" * (protocol.MAX_FILE_CHUNK_PAYLOAD_SIZE + 1)

    with pytest.raises(ValueError, match="exceeds the binary-v2 maximum"):
        protocol.encode_file_chunk(b"x" * 12, 0, oversized)


def test_structured_payload_rejects_oversized_messages():
    oversized_json = json.dumps({"type": "chat", "body": "a" * (protocol.MAX_STRUCTURED_PAYLOAD_SIZE + 10)}).encode("utf-8")
    with pytest.raises(ValueError, match="structured payload size.*exceeds maximum limit"):
        protocol.decode_message(oversized_json)

