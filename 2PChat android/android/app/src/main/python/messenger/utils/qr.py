"""QR helpers for CLI/GUI flows.

The payloads here are strictly public identity data; no secrets are embedded.
"""

from __future__ import annotations

import warnings

try:  # pragma: no cover - optional dependency branch
    import qrcode
except Exception:  # noqa: BLE001 pragma: no cover
    qrcode = None


def _require_qrcode():
    if qrcode is None:
        raise RuntimeError(
            "qrcode is not installed. Run `pip install qrcode[pil]` to enable QR rendering."
        )


def render_qr_ascii(payload: str) -> str:
    """Return an ASCII-art QR code for a payload or a readable fallback string."""

    if qrcode is None:
        warnings.warn("qrcode not installed; returning fallback string")
        return f"[QR unavailable]\n{payload}"

    qr = qrcode.QRCode(border=1)
    qr.add_data(payload)
    qr.make(fit=True)
    matrix = qr.get_matrix()
    lines = []
    for row in matrix:
        lines.append("".join("██" if cell else "  " for cell in row))
    return "\n".join(lines)


def save_qr_png(payload: str, path: str) -> None:
    """Save a PNG QR code if qrcode/Pillow are installed."""

    _require_qrcode()
    img = qrcode.make(payload)
    img.save(path)


__all__ = ["render_qr_ascii", "save_qr_png"]
