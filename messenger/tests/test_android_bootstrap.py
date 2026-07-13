import importlib.util
from pathlib import Path


def _load_android_bootstrap():
    root = Path(__file__).resolve().parents[2]
    path = root / "2PChat android" / "android" / "app" / "src" / "main" / "python" / "bootstrap.py"
    spec = importlib.util.spec_from_file_location("android_bootstrap", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_android_log_rotation_keeps_one_backup(tmp_path):
    bootstrap = _load_android_bootstrap()
    log_file = tmp_path / "app.log"
    log_file.write_bytes(b"x" * bootstrap.MAX_LOG_BYTES)

    bootstrap._rotate_log_if_needed(log_file, 1)

    assert not log_file.exists()
    assert (tmp_path / "app.log.1").stat().st_size == bootstrap.MAX_LOG_BYTES
