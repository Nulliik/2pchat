import logging


def setup_logger(name: str = "messenger", level: int = logging.INFO) -> logging.Logger:
    """Configure and return a simple console logger.

    If the logger already exists, its level is refreshed so callers can bump
    verbosity (e.g., from --verbose flags) without reconfiguring handlers.
    """

    logger = logging.getLogger(name)
    logger.setLevel(level)

    if not logger.handlers:
        handler = logging.StreamHandler()
        formatter = logging.Formatter(
            "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
        )
        handler.setFormatter(formatter)
        logger.addHandler(handler)
        logger.propagate = True

    return logger
