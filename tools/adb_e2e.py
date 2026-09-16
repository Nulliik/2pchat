"""Token-efficient adb harness for 2PChat emulator testing.

Why: running `adb logcat -d` dumps raw buffers into the agent context; each
debug command costs hundreds of tokens. This wrapper keeps all verbose output
in files and prints only compact JSON facts.

Usage:
  python tools/adb_e2e.py devices
  python tools/adb_e2e.py op <serial> <op> [key=value ...]   # one control broadcast
  python tools/adb_e2e.py batch <serial> 'op1 k=v' 'op2 k=v k2=v2'   # several ops, ONE broadcast
  python tools/adb_e2e.py logcat <serial> [TAG] [--lines N]  # dump to logs/, print compact tail

Requires a *debug* APK (E2EControlReceiver exists only in debug builds).
The receiver replies with one JSON line logged to tag 2PChatE2E.
"""
import base64
import json
import os
import shlex
import subprocess
import sys
import time
import uuid

ADB = os.environ.get("ADB") or (os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe") if os.name == "nt" else "adb")
TAG = "2PChatE2E"


def adb(serial, *args, timeout=60):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stdout}\n{result.stderr}")
    return result.stdout


def op(serial, op_name, **extras):
    """Broadcast one control op and return its JSON reply. Prints nothing itself.

    The receiver only answers in the isolated groupqa package; E2E_PACKAGE can
    override it. Failed ops raise with the receiver's own error text."""
    package = os.environ.get("E2E_PACKAGE", "com.example.twopchat.groupqa")
    marker = uuid.uuid4().hex[:8]  # run tracer: ties the log line to this exact command
    before = adb(serial, "logcat", "-d", "-s", f"{TAG}:I").splitlines()
    cmd = ["am", "broadcast", "--include-stopped-packages", "--receiver-foreground",
           "-n", f"{package}/com.example.twopchat.debug.E2EControlReceiver",
           "-a", "com.example.twopchat.debug.GROUP", "--es", "op", op_name, "--es", "trace", marker]
    for key, value in extras.items():
        cmd += ["--es", key, str(value)]
    adb(serial, "shell", shlex.join(cmd))
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        for line in adb(serial, "logcat", "-d", "-s", f"{TAG}:I").splitlines()[len(before):]:
            if '{"action":' not in line:
                continue
            payload = line[line.index('{"action":'):]
            try:
                result = json.loads(payload)
            except json.JSONDecodeError:
                continue
            if result.get("action") == "com.example.twopchat.debug.GROUP":
                if not result.get("ok"):
                    raise RuntimeError(f"{serial}: op {op_name} failed: {result.get('error')}")
                result["trace"] = marker
                return result
        time.sleep(0.25)
    raise TimeoutError(f"{serial}: op {op_name} ({marker}) produced no reply")


def batch(serial, steps):
    """Run several receiver ops in ONE broadcast; returns the list of step replies.

    Steps are JSON+base64 encoded (ops_b64) to survive adb/PowerShell quoting."""
    payload = base64.b64encode(json.dumps(steps, ensure_ascii=False).encode()).decode()
    reply = op(serial, "batch", ops_b64=payload)
    return reply["results"]


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "devices"
    if cmd == "devices":
        print(adb("", "devices", "-l").strip())
        return
    if cmd == "op":
        serial, op_name = sys.argv[2], sys.argv[3]
        extras = dict(arg.split("=", 1) for arg in sys.argv[4:] if "=" in arg)
        print(json.dumps(op(serial, op_name, **extras), ensure_ascii=False))
        return
    if cmd == "batch":
        serial = sys.argv[2]
        steps = []
        for spec in sys.argv[3:]:
            tokens = spec.split()
            step = {"op": tokens[0]}
            for token in tokens[1:]:
                key, value = token.split("=", 1)
                step[key] = value
            steps.append(step)
        print(json.dumps({"results": batch(serial, steps)}, ensure_ascii=False))
        return
    if cmd == "logcat":
        serial = sys.argv[2]
        tag = sys.argv[3] if len(sys.argv) > 3 else "2PChatE2E"
        lines = int(sys.argv[sys.argv.index("--lines") + 1]) if "--lines" in sys.argv else 15
        out = os.path.join("logs", f"logcat-{serial}-{int(time.time())}.txt")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        full = adb(serial, "logcat", "-d", "-s", f"{tag}:V")
        tail = [l for l in adb(serial, "logcat", "-d", "-s", f"{tag}:I").strip().splitlines() if l][-lines:]
        with open(out, "w", encoding="utf-8") as file:
            file.write(full)
        print(json.dumps({"saved": out, "tail": tail}, ensure_ascii=False))
        return
    print(__doc__)
    sys.exit(2)


if __name__ == "__main__":
    main()
