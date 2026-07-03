import re

from messenger.core.discovery_naming import generate_discovery_key, generate_discovery_name


def test_generate_discovery_name_uses_seed_and_suffix():
    generated = generate_discovery_name("Alice Cooper")

    assert generated.startswith("alice-cooper-")
    assert re.fullmatch(r"[a-z0-9-]+", generated)
    assert len(generated.split("-")[-1]) == 4


def test_generate_discovery_name_falls_back_for_empty_seed():
    generated = generate_discovery_name("   ")

    assert generated.startswith("contact-")


def test_generate_discovery_key_uses_grouped_random_format():
    generated = generate_discovery_key()

    assert re.fullmatch(r"[23456789bcdfghjkmnpqrstvwxyz]{4}(?:-[23456789bcdfghjkmnpqrstvwxyz]{4}){2}", generated)
