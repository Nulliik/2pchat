"""Real two-emulator group regression. Requires the isolated groupqa debug APK."""
import argparse
import hashlib
import json
import os
import shlex
import subprocess
import time

PACKAGE = "com.example.twopchat.groupqa"
ACTION = "com.example.twopchat.debug.GROUP"
ADB = os.environ.get("ADB", "adb")


def adb(serial, *args):
    try:
        return subprocess.check_output([ADB, "-s", serial, *args], text=True, encoding="utf-8", errors="replace", stderr=subprocess.STDOUT, timeout=30)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(error.output) from error


def control(serial, op, **extras):
    before = adb(serial, "logcat", "-d", "-s", "2PChatE2E:I").splitlines()
    cmd = ["am", "broadcast", "--include-stopped-packages", "-n", f"{PACKAGE}/com.example.twopchat.debug.E2EControlReceiver", "-a", ACTION, "--es", "op", op]
    for key, value in extras.items():
        cmd += ["--es", key, str(value)]
    adb(serial, "shell", shlex.join(cmd))
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        lines = adb(serial, "logcat", "-d", "-s", "2PChatE2E:I").splitlines()
        for line in lines[len(before):]:
            if '{"action":' in line:
                result = json.loads(line[line.index('{"action":'):])
                if result.get("action") == ACTION:
                    assert result.get("ok"), result
                    return result
        time.sleep(.25)
    raise TimeoutError((serial, op))


def wait_for(serial, group, predicate, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = control(serial, "status", group=group)
        if predicate(state):
            return state
        time.sleep(.5)
    raise AssertionError(f"State did not converge: {state}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--first", default="emulator-5554")
    parser.add_argument("--second", default="emulator-5556")
    args = parser.parse_args()
    a, b = args.first, args.second
    for serial in (a, b):
        adb(serial, "shell", "monkey", "-p", PACKAGE, "1")
    ia = control(a, "setup", name="GroupAlice")
    ib = control(b, "setup", name="GroupBob")
    adb(a, "forward", "tcp:55154", f"tcp:{ia['port']}")
    adb(b, "forward", "tcp:55156", f"tcp:{ib['port']}")
    control(a, "connect", name="GroupBob", fingerprint=ib["fingerprint"], endpoint="10.0.2.2:55156")
    control(b, "connect", name="GroupAlice", fingerprint=ia["fingerprint"], endpoint="10.0.2.2:55154")
    wait_for(a, "", lambda s: any(s["peers"].values()))
    wait_for(b, "", lambda s: any(s["peers"].values()))
    gid = control(a, "create", title=f"ADB-{int(time.time())}", contacts="GroupBob")["group"]
    deadline = time.monotonic() + 30
    while not control(b, "accept", group=gid).get("accepted"):
        assert time.monotonic() < deadline, "invite was not delivered"
        time.sleep(1)
    wait_for(b, gid, lambda s: len(s["members"]) == 2 and s["composer"])
    control(a, "send", group=gid, text="Hello group")
    state = wait_for(b, gid, lambda s: any(m["text"] == "Hello group" for m in s["messages"]))
    mid = next(m["id"] for m in state["messages"] if m["text"] == "Hello group")
    print("PASS invite/accept/text", flush=True)
    control(a, "edit", group=gid, message=mid, text="Edited group")
    wait_for(b, gid, lambda s: any(m["text"] == "Edited group" and m["edited"] for m in s["messages"]))
    control(b, "send", group=gid, text="Reply from Bob", reply=mid)
    wait_for(a, gid, lambda s: any(m.get("reply") == mid for m in s["messages"]))
    control(b, "react", group=gid, message=mid, emoji="👍")
    wait_for(a, gid, lambda s: any(m["id"] == mid and m["reactions"] for m in s["messages"]))
    control(a, "pin", group=gid, message=mid)
    wait_for(b, gid, lambda s: any(m["id"] == mid and m["pinned"] for m in s["messages"]))
    print("PASS edit/reply/reaction/pin", flush=True)
    control(a, "poll", group=gid, text="Poll question")
    state = wait_for(b, gid, lambda s: any("votes" in m for m in s["messages"]))
    poll = next(m["id"] for m in state["messages"] if "votes" in m)
    control(b, "vote", group=gid, message=poll, option=0)
    wait_for(a, gid, lambda s: any(m.get("votes") == 1 for m in s["messages"]))
    print("PASS poll/vote", flush=True)
    control(a, "typing", group=gid, value="true")
    wait_for(b, gid, lambda s: bool(s["typing"]), timeout=4)
    wait_for(b, gid, lambda s: not s["typing"], timeout=8)
    control(b, "active", group=gid, value="true")
    wait_for(a, gid, lambda s: any(m["id"] == mid and m["read"] for m in s["messages"]))
    control(a, "unpin", group=gid, message=mid)
    wait_for(b, gid, lambda s: any(m["id"] == mid and not m["pinned"] for m in s["messages"]))
    print("PASS typing/expiry/read/unpin", flush=True)
    control(a, "attachment", group=gid)
    state = wait_for(b, gid, lambda s: any(m["text"] == "ADB attachment" for m in s["messages"]))
    attachment = next(m["id"] for m in state["messages"] if m["text"] == "ADB attachment")
    control(b, "download", group=gid, message=attachment)
    state = wait_for(b, gid, lambda s: any(m["id"] == attachment and m.get("downloaded") for m in s["messages"]), timeout=90)
    path = next(m["path"] for m in state["messages"] if m["id"] == attachment)
    data = subprocess.check_output([ADB, "-s", b, "exec-out", "run-as", PACKAGE, "cat", path], timeout=30)
    expected = bytes(i % 251 for i in range(1_048_613))
    assert hashlib.sha256(data).digest() == hashlib.sha256(expected).digest()
    print("PASS attachment 1048613 bytes / SHA-256", flush=True)
    # Process loss and durable recovery through the actual pairwise transport.
    adb(b, "shell", f"am force-stop {PACKAGE}")
    control(a, "send", group=gid, text="Offline delivery")
    control(b, "setup", name="GroupBob")
    control(b, "connect", name="GroupAlice", fingerprint=ia["fingerprint"], endpoint="10.0.2.2:55154")
    control(b, "sync", group=gid)
    wait_for(b, gid, lambda s: any(m["text"] == "Offline delivery" for m in s["messages"]), timeout=90)
    print("PASS offline/restart/recovery", flush=True)
    control(a, "delete", group=gid, message=mid)
    wait_for(b, gid, lambda s: any(m["id"] == mid and m["text"] == "Message deleted" for m in s["messages"]))
    print("PASS delete; group=" + gid, flush=True)
    state = control(a, "status", group=gid)
    bob = next(m["id"] for m in state["members"] if m["name"] == "GroupBob")
    control(a, "role", group=gid, member=bob, role="ADMINISTRATOR")
    wait_for(b, gid, lambda s: any(m["id"] == bob and m["role"] == "ADMINISTRATOR" for m in s["members"]))
    control(a, "transfer", group=gid, member=bob)
    wait_for(b, gid, lambda s: any(m["id"] == bob and m["role"] == "OWNER" for m in s["members"]))
    control(a, "leave", group=gid)
    wait_for(b, gid, lambda s: len(s["members"]) == 1)
    print("PASS role/ownership/leave", flush=True)


if __name__ == "__main__":
    main()
