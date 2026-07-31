import os
import sys
from pathlib import Path
import datetime
import re

MAX_LOG_BYTES = 5 * 1024 * 1024

_IPV4_RE = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?")
_IPV6_RE = re.compile(r"\[[0-9a-fA-F:]+\](?::\d{1,5})?")
_FINGERPRINT_RE = re.compile(r"(?i)(?<![0-9a-f])[0-9a-f]{40,128}(?![0-9a-f])")


def _redact_sensitive_log_text(text):
    text = _IPV4_RE.sub("<ip>", text)
    text = _IPV6_RE.sub("<ip>", text)
    text = _FINGERPRINT_RE.sub("<fingerprint>", text)
    private_root = os.environ.get("P2PCHAT_CONFIG_DIR")
    if private_root:
        text = text.replace(str(Path(private_root).parent), "<app-private-dir>")
    return text


def _rotate_log_if_needed(log_file_path, incoming_bytes):
    try:
        if log_file_path.exists() and log_file_path.stat().st_size + incoming_bytes > MAX_LOG_BYTES:
            backup = log_file_path.with_name("app.log.1")
            backup.unlink(missing_ok=True)
            log_file_path.replace(backup)
    except OSError:
        # Logging must never break the messaging runtime.
        pass

class LogRedirector:
    def __init__(self, original_stream, log_file_path, prefix):
        self.original_stream = original_stream
        self.log_file_path = log_file_path
        self.prefix = prefix

    def write(self, data):
        if self.original_stream:
            self.original_stream.write(data)
        stripped = data.strip()
        if stripped:
            try:
                timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S,%f")[:-3]
                # Log to app.log
                line = f"{timestamp} [{self.prefix}] {_redact_sensitive_log_text(stripped)}\n"
                _rotate_log_if_needed(self.log_file_path, len(line.encode("utf-8")))
                with open(self.log_file_path, "a", encoding="utf-8") as f:
                    f.write(line)
            except Exception:
                pass

    def flush(self):
        if self.original_stream:
            self.original_stream.flush()

def set_config_dir(path):
    os.environ["P2PCHAT_CONFIG_DIR"] = path
    log_dir = Path(path)
    log_dir.mkdir(parents=True, exist_ok=True)
    log_file = log_dir / "app.log"
    
    # Redirect stdout and stderr
    sys.stdout = LogRedirector(sys.stdout, log_file, "PYTHON_OUT")
    sys.stderr = LogRedirector(sys.stderr, log_file, "PYTHON_ERR")
    
    print(f"Bootstrap: P2PCHAT_CONFIG_DIR set to {path}. Stdout/Stderr redirected to app.log.")
