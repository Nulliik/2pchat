import logging
import os
from pathlib import Path

# Shared FileHandler to avoid lock conflicts and multiple file openings
_shared_file_handler = None


def setup_logger(name: str = "messenger", level: int = logging.INFO) -> logging.Logger:
    """Configure and return a simple console logger.

    If the logger already exists, its level is refreshed so callers can bump
    verbosity (e.g., from --verbose flags) without reconfiguring handlers.
    """
    global _shared_file_handler

    logger = logging.getLogger(name)
    logger.setLevel(level)

    if not logger.handlers:
        handler = logging.StreamHandler()
        formatter = logging.Formatter(
            "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
        )
        handler.setFormatter(formatter)
        logger.addHandler(handler)

        # File Handler for Android persistent log file
        config_dir = os.environ.get("P2PCHAT_CONFIG_DIR")
        if config_dir:
            if _shared_file_handler is None:
                try:
                    log_file = Path(config_dir) / "app.log"
                    # Open/create the file immediately (delay=False) so it is ready on startup
                    _shared_file_handler = logging.FileHandler(str(log_file), encoding="utf-8", delay=False)
                    _shared_file_handler.setFormatter(formatter)
                except Exception as e:
                    print(f"Failed to setup file logging to {log_file}: {e}")
            
            if _shared_file_handler is not None:
                logger.addHandler(_shared_file_handler)

        logger.propagate = False

    return logger
