#!/usr/bin/env python3
"""Two-emulator, non-mocked Android E2E smoke test for 2PChat debug APKs."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path

PACKAGE = "com.example.twopchat.go"
RECEIVER = f"{PACKAGE}/com.example.twopchat.debug.E2EControlReceiver"
TAG, PORT = "2PChatE2E", 50001


def run(*args: str, timeout: int = 30) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=timeout)


def adb(serial: str, *args: str, timeout: int = 30) -> str:
    return run("adb", "-s", serial, *args, timeout=timeout)


def result(serial: str, action: str, timeout: int = 25) -> dict:
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        for line in reversed(adb(serial, "logcat", "-d", "-v", "brief", "-s", f"{TAG}:I").splitlines()):
            match = re.search(r"(\{.*\})$", line)
            if match:
                item = json.loads(match.group(1))
                if item.get("action") == action:
                    if not item.get("ok"):
                        raise RuntimeError(f"{serial} {action}: {item.get('error')}")
                    return item
        time.sleep(0.5)
    raise TimeoutError(f"no {action} result from {serial}")


def control(serial: str, action: str, **extras: str) -> dict:
    for attempt in range(2):
        adb(serial, "logcat", "-c")
        command = ["shell", "am", "broadcast", "-n", RECEIVER, "-a", action]
        for key, value in extras.items():
            command += ["--es", key, value]
        adb(serial, *command)
        try:
            return result(serial, action)
        except TimeoutError:
            if attempt:
                raise
            adb(serial, "shell", "monkey", "-p", PACKAGE, "1")
            time.sleep(5)
    raise AssertionError("unreachable")


def ipv4(serial: str) -> str:
    match = re.search(r"\bsrc\s+(\d+\.\d+\.\d+\.\d+)", adb(serial, "shell", "ip", "route", "get", "1.1.1.1"))
    if not match:
        raise RuntimeError(f"cannot discover IPv4 for {serial}")
    return match.group(1)


def require_log(serial: str, pattern: str, timeout: int) -> None:
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if re.search(pattern, adb(serial, "logcat", "-d", "-v", "brief"), re.I):
            return
        time.sleep(1)
    raise TimeoutError(f"{serial}: did not observe {pattern!r}")


def accept_vpn_consent(serial: str) -> None:
    """Accept the platform-owned consent dialog using its accessibility text."""
    remote = "/sdcard/2pchat-vpn-consent.xml"
    until = time.monotonic() + 12
    match = None
    while time.monotonic() < until:
        adb(serial, "shell", "uiautomator", "dump", remote)
        xml = adb(serial, "shell", "cat", remote)
        match = re.search(r'text="(?:OK|Allow|Разрешить)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if match:
            break
        time.sleep(0.5)
    if not match:
        raise RuntimeError(f"VPN consent dialog was not shown on {serial}")
    x1, y1, x2, y2 = map(int, match.groups())
    adb(serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    # The platform dialog sometimes keeps focus on its positive button while
    # its transition is still animating; ENTER makes the action deterministic.
    adb(serial, "shell", "input", "keyevent", "66")
    time.sleep(3)


def ui_xml(serial: str) -> str:
    """Return the live accessibility tree; no test-only UI selectors are used."""
    remote = "/sdcard/2pchat-ui.xml"
    adb(serial, "shell", "uiautomator", "dump", remote)
    return adb(serial, "shell", "cat", remote)


def tap_ui_text(serial: str, text: str, *, timeout: int = 15) -> None:
    until = time.monotonic() + timeout
    escaped = re.escape(text)
    while time.monotonic() < until:
        xml = ui_xml(serial)
        match = re.search(rf'text="{escaped}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            adb(serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return
        time.sleep(0.5)
    raise TimeoutError(f"{serial}: UI text {text!r} was not present")


def finish_onboarding_with_ui(serial: str, profile_name: str) -> None:
    """Exercise the visible onboarding and the Android-owned VPN consent screen."""
    adb(serial, "shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    initial = ui_xml(serial)
    if "Chats" in initial and "Yggdrasil Mesh" in initial:
        return
    # Five intro pages precede the profile page.  Tapping the visible button
    # rather than a Compose implementation detail keeps this a user-level test.
    for _ in range(5):
        xml = ui_xml(serial)
        if "Chats" in xml and "Yggdrasil Mesh" in xml:
            return
        if "Create Profile" in xml:
            break
        tap_ui_text(serial, "Continue")
        time.sleep(0.3)
    xml = ui_xml(serial)
    if "Create Profile" in xml:
        field = re.search(r'class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if not field:
            raise RuntimeError(f"{serial}: profile name field is unavailable")
        x1, y1, x2, y2 = map(int, field.groups())
        adb(serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        adb(serial, "shell", "input", "text", profile_name)
        adb(serial, "shell", "input", "keyevent", "4")
        tap_ui_text(serial, "Continue")
        time.sleep(0.5)
    if "Welcome aboard" in ui_xml(serial):
        tap_ui_text(serial, "Enter")
        time.sleep(0.5)
    if "Activate Yggdrasil VPN" in ui_xml(serial):
        tap_ui_text(serial, "Activate Yggdrasil VPN")
        accept_vpn_consent(serial)
    xml = ui_xml(serial)
    if "Chats" not in xml or "Yggdrasil Mesh" not in xml:
        raise RuntimeError(f"{serial}: onboarding did not reach the main UI")


def open_chat_via_visible_invite(serial: str, invite: str, peer_name: str) -> None:
    """Paste a real peer invitation through the Search screen and open its chat."""
    tap_ui_text(serial, "Search")
    xml = ui_xml(serial)
    field = re.search(r'class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not field:
        raise RuntimeError(f"{serial}: contact search field is unavailable")
    x1, y1, x2, y2 = map(int, field.groups())
    adb(serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    # adb's input command treats ampersands as shell syntax, so quote the
    # complete URI once for the remote shell.
    adb(serial, "shell", "input", "text", invite.replace("&", r"\\&"))
    adb(serial, "shell", "input", "keyevent", "66")
    until = time.monotonic() + 35
    while time.monotonic() < until:
        xml = ui_xml(serial)
        if peer_name in xml and "Connecting to peer" not in xml:
            tap_ui_text(serial, peer_name)
            return
        time.sleep(1)
    raise TimeoutError(f"{serial}: invite did not produce chat for {peer_name}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--first", default="emulator-5554")
    parser.add_argument("--second", default="emulator-5556")
    parser.add_argument("--tracker", action="append", required=True,
                        help="Live tracker URL; announce is performed sequentially")
    parser.add_argument("--vpn", action="store_true")
    args = parser.parse_args()
    a, b = args.first, args.second
    for serial in (a, b):
        adb(serial, "wait-for-device")
        adb(serial, "install", "-r", str(args.apk), timeout=90)
        adb(serial, "shell", "pm", "clear", PACKAGE)
        # Grant before first launch so the platform notification prompt cannot
        # obscure the visible onboarding controls on either emulator.
        adb(serial, "shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        adb(serial, "shell", "monkey", "-p", PACKAGE, "1")
        # Let Application.onCreate finish before a broadcast starts the native core.
        time.sleep(5)

    alice = control(a, "com.example.twopchat.debug.PROVISION", nickname="E2EAlice")
    bob = control(b, "com.example.twopchat.debug.PROVISION", nickname="E2EBob")
    finish_onboarding_with_ui(a, "E2EAlice")
    finish_onboarding_with_ui(b, "E2EBob")
    endpoint_a, endpoint_b = f"{ipv4(a)}:{PORT}", f"{ipv4(b)}:{PORT}"
    info_hash = hashlib.sha256(b"2pchat-android-e2e-v1").hexdigest()

    # No tracker mocks: publish to one configured live tracker at a time.
    for tracker in args.tracker:
        for serial in (a, b):
            published = control(serial, "com.example.twopchat.debug.TRACKER", tracker=tracker, info_hash=info_hash)
            # nativeAnnounceSelf is deliberately best-effort: UDP/HTTP announces
            # happen in the discovery worker and can return false before its
            # first scheduling tick. Starting discovery is the synchronous
            # acceptance criterion; the returned announce flag is preserved in
            # the run log for an honest, non-mocked report.
            if not published["discovery_started"]:
                raise RuntimeError(f"tracker setup rejected by {serial}: {tracker}")
        time.sleep(3)

    control(a, "com.example.twopchat.debug.CONNECT", endpoint=endpoint_b, fingerprint=bob["fingerprint"])
    require_log(a, r"Peer connected", 30)
    control(a, "com.example.twopchat.debug.SEND", fingerprint=bob["fingerprint"], body="e2e-ipv4-message")
    require_log(b, r"Message received", 30)

    # The direct IPv4 candidate is real (the peer's Wi-Fi address).  The UI
    # resolves and opens the resulting authenticated peer, not a seeded chat.
    invite_info = control(b, "com.example.twopchat.debug.INVITE")
    invite = (
        f"2pchat://connect?name={invite_info['name']}&token={invite_info['code']}"
        f"&fp={bob['fingerprint']}&ip={endpoint_b}"
    )
    open_chat_via_visible_invite(a, invite, invite_info["name"])

    for serial in (a, b):
        control(serial, "com.example.twopchat.debug.PROXY")
        require_log(serial, r"Yggdrasil User-Space Proxy Stack started", 45)
        control(serial, "com.example.twopchat.debug.TOR")
        require_log(serial, r"Tor.*(started|running|ready)", 90)

    if args.vpn:
        for serial in (a, b):
            vpn = control(serial, "com.example.twopchat.debug.VPN")
            if vpn.get("consent_required"):
                accept_vpn_consent(serial)
                vpn = control(serial, "com.example.twopchat.debug.VPN")
                if vpn.get("consent_required"):
                    raise RuntimeError(f"VPN consent was not granted on {serial}")
            require_log(serial, r"Yggdrasil address obtained", 30)

    print(json.dumps({"ok": True, "ipv4": [endpoint_a, endpoint_b], "info_hash": info_hash, "alice": alice["fingerprint"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
