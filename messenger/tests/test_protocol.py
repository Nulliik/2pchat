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
