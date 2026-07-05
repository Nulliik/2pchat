import base64
import os

import pytest

from messenger.core import crypto


def _salt() -> bytes:
    return os.urandom(16)


def test_argon2_derivation_returns_32_bytes():
    salt = _salt()
    key, metadata = crypto.derive_channel_key("password", salt, kdf=crypto.KDF_ARGON2ID)

    assert len(key) == 32
    assert metadata["kdf"] == crypto.KDF_ARGON2ID
    assert metadata["salt"] == base64.b64encode(salt).decode()


def test_scrypt_derivation_returns_32_bytes():
    salt = _salt()
    key, metadata = crypto.derive_channel_key("password", salt, kdf=crypto.KDF_SCRYPT)

    assert len(key) == 32
    assert metadata["kdf"] == crypto.KDF_SCRYPT


def test_rotation_upgrades_to_memory_hard_kdf():
    salt = _salt()
    old_key, old_metadata = crypto.derive_channel_key(
        "oldpass", salt, kdf=crypto.KDF_SCRYPT
    )

    new_key, new_metadata = crypto.rotate_channel_password("oldpass", "newpass", old_metadata)

    assert new_key != old_key
    assert new_metadata["kdf"] == crypto.KDF_ARGON2ID
    assert new_metadata["salt"] != old_metadata["salt"]


def test_different_salts_produce_different_keys():
    salt1 = _salt()
    salt2 = _salt()
    while salt1 == salt2:  # extremely unlikely, but deterministic if it happens
        salt2 = _salt()

    key1, _ = crypto.derive_channel_key("password", salt1, kdf=crypto.KDF_ARGON2ID)
    key2, _ = crypto.derive_channel_key("password", salt2, kdf=crypto.KDF_ARGON2ID)

    assert key1 != key2


def test_legacy_metadata_is_rejected():
    salt = _salt()
    metadata = {
        "kdf": "pbkdf2-sha256-legacy",
        "params": {"iterations": 100_000},
        "salt": base64.b64encode(salt).decode(),
    }

    with pytest.raises(ValueError):
        crypto.derive_channel_key_from_metadata("secret", metadata)
