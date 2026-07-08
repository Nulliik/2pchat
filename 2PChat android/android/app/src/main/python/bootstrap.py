import os
import sys
from pathlib import Path
import datetime

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
                with open(self.log_file_path, "a", encoding="utf-8") as f:
                    f.write(f"{timestamp} [{self.prefix}] {stripped}\n")
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
