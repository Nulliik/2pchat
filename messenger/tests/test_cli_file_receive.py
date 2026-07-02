import base64
import os

from messenger.app.cli_chat import FileReceiver
from messenger.core.crypto import encrypt_file_in_chunks


def test_cli_file_receiver_saves_file(tmp_path):
    receiver = FileReceiver(tmp_path)

    src = tmp_path / "sample.bin"
    data = os.urandom(2048)
    src.write_bytes(data)

    (
        chunk_iter,
        file_key,
        file_nonce_prefix,
        file_size,
        num_chunks,
        file_hash,
    ) = encrypt_file_in_chunks(str(src), chunk_size=256)

    file_id = os.urandom(12)
    file_id_b64 = base64.b64encode(file_id).decode()

    meta = {
        "type": "file_meta",
        "file_id": file_id_b64,
        "file_name": "sample.bin",
        "file_size": file_size,
        "num_chunks": num_chunks,
        "file_hash": base64.b64encode(file_hash).decode(),
        "file_key": base64.b64encode(file_key).decode(),
        "file_nonce_prefix": base64.b64encode(file_nonce_prefix).decode(),
        "timestamp": 0,
    }

    processed, info = receiver.handle(meta)
    assert processed
    assert "Incoming file" in (info or "")

    last_info = None
    for idx, chunk in chunk_iter:
        processed, info = receiver.handle(
            {
                "type": "file_chunk",
                "file_id": file_id_b64,
                "chunk_index": idx,
                "payload": base64.b64encode(chunk).decode(),
            }
        )
        last_info = info or last_info

    saved_path = tmp_path / "sample.bin"
    assert saved_path.exists()
    assert saved_path.read_bytes() == data
    assert processed is True
    assert last_info is None or "Saved file" in last_info
