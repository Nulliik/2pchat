"""Run android_group_e2e.main() with an exact adb call/byte counter, then print counts."""
import json
import sys
import time

sys.path.insert(0, "2pchatGO/android/scripts")
import android_group_e2e as legacy

CALLS = {"n": 0, "bytes": 0}
_orig = legacy.adb


def metered(serial, *args):
    out = _orig(serial, *args)
    CALLS["n"] += 1
    CALLS["bytes"] += len(out or "")
    return out


legacy.adb = metered
t0 = time.monotonic()
try:
    legacy.main()
    status = "PASS"
except Exception as error:  # noqa: BLE001 - measurement wrapper
    status = f"FAIL: {error}"
CALLS["seconds"] = round(time.monotonic() - t0, 1)
CALLS["status"] = status
print(json.dumps(CALLS))
