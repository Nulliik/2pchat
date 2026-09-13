"""Two-emulator overlay transport regression for the isolated groupqa debug package."""
import json
import shlex
import time

from android_group_e2e import ACTION, PACKAGE, adb, control

A, B = "emulator-5554", "emulator-5556"


def action(serial, name):
    cmd = ["am", "broadcast", "--include-stopped-packages", "-n",
           f"{PACKAGE}/com.example.twopchat.debug.E2EControlReceiver", "-a", name]
    adb(serial, "shell", shlex.join(cmd))


def wait_state(serial, op, predicate, timeout=120, **extras):
    end = time.monotonic() + timeout
    last = {}
    while time.monotonic() < end:
        last = control(serial, op, **extras)
        if predicate(last):
            return last
        time.sleep(1)
    raise AssertionError(f"{serial} {op} did not converge: {json.dumps(last)}")


def has_ygg(state):
    value = state.get("ygg", "")
    return value.startswith("2") or value.startswith("3")


def main():
    for serial in (A, B):
        adb(serial, "install", "-r", r"app/build/outputs/apk/debug/app-debug.apk")
        adb(serial, "shell", "pm", "clear", PACKAGE)
        adb(serial, "shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        adb(serial, "logcat", "-c")
        adb(serial, "shell", "monkey", "-p", PACKAGE, "1")
    time.sleep(1)
    ia = control(A, "setup", name="OverlayAlice", port="50001")
    ib = control(B, "setup", name="OverlayBob", port="50001")
    adb(A, "forward", "tcp:55154", "tcp:50001")
    adb(B, "forward", "tcp:55156", "tcp:50001")
    control(A, "peer_seed", name="OverlayBob", fingerprint=ib["fingerprint"], endpoints="10.0.2.2:55156")
    control(B, "peer_seed", name="OverlayAlice", fingerprint=ia["fingerprint"], endpoints="10.0.2.2:55154")
    control(A, "connect", name="OverlayBob", fingerprint=ib["fingerprint"], endpoint="10.0.2.2:55156")
    control(B, "connect", name="OverlayAlice", fingerprint=ia["fingerprint"], endpoint="10.0.2.2:55154")
    wait_state(A, "peer_status", lambda s: s["online"], name="OverlayBob")
    wait_state(B, "peer_status", lambda s: s["online"], name="OverlayAlice")
    print("PASS direct authenticated baseline", flush=True)

    for serial in (A, B):
        action(serial, "com.example.twopchat.debug.PROXY")
    sa = wait_state(A, "network_status", has_ygg)
    sb = wait_state(B, "network_status", has_ygg)
    print("PASS Yggdrasil live", sa["ygg"], sb["ygg"], flush=True)

    # The peer session remains open. This verifies that a new overlay address is
    # propagated without reconnecting the direct route.
    assert control(A, "announce")["accepted"]
    assert control(B, "announce")["accepted"]
    wait_state(A, "peer_status", lambda s: any(r["endpoint"] == f"[{sb['ygg']}]:50001" and r["source"] == "AUTHENTICATED" for r in s["records"]), name="OverlayBob")
    wait_state(B, "peer_status", lambda s: any(r["endpoint"] == f"[{sa['ygg']}]:50001" and r["source"] == "AUTHENTICATED" for r in s["records"]), name="OverlayAlice")
    print("PASS live Yggdrasil route exchange over existing encrypted sessions", flush=True)

    assert control(A, "peer_transport", name="OverlayBob", mode="YGGDRASIL_ONLY")["accepted"]
    assert control(B, "peer_transport", name="OverlayAlice", mode="YGGDRASIL_ONLY")["accepted"]
    wait_state(A, "peer_status", lambda s: s["online"], timeout=45, name="OverlayBob")
    wait_state(B, "peer_status", lambda s: s["online"], timeout=45, name="OverlayAlice")
    assert control(A, "peer_send", name="OverlayBob", text="Ygg-only A to B")["accepted"]
    assert control(B, "peer_send", name="OverlayAlice", text="Ygg-only B to A")["accepted"]
    wait_state(B, "peer_status", lambda s: any(m["text"] == "Ygg-only A to B" for m in s["messages"]), name="OverlayAlice")
    wait_state(A, "peer_status", lambda s: any(m["text"] == "Ygg-only B to A" for m in s["messages"]), name="OverlayBob")
    print("PASS bidirectional Yggdrasil-only chat", flush=True)

    # Re-establish a direct authenticated session before introducing the Tor
    # route; the Ygg-only test intentionally closed it.
    for serial, name, fp, endpoint in (
        (A, "OverlayBob", ib["fingerprint"], "10.0.2.2:55156"),
        (B, "OverlayAlice", ia["fingerprint"], "10.0.2.2:55154"),
    ):
        assert control(serial, "peer_transport", name=name, mode="AUTO")["accepted"]
        assert control(serial, "connect", name=name, fingerprint=fp, endpoint=endpoint)["accepted"]
    wait_state(A, "peer_status", lambda s: s["online"], timeout=45, name="OverlayBob")
    wait_state(B, "peer_status", lambda s: s["online"], timeout=45, name="OverlayAlice")

    for serial in (A, B):
        action(serial, "com.example.twopchat.debug.TOR")
    ta = wait_state(A, "network_status", lambda s: s["tor_running"] and bool(s["onion"]), timeout=180)
    tb = wait_state(B, "network_status", lambda s: s["tor_running"] and bool(s["onion"]), timeout=180)
    print("PASS Tor live", ta["onion"], tb["onion"], flush=True)
    assert control(A, "announce")["accepted"]
    assert control(B, "announce")["accepted"]
    wait_state(A, "peer_status", lambda s: any(r["endpoint"] == f"{tb['onion']}:50001" and r["source"] == "AUTHENTICATED" for r in s["records"]), name="OverlayBob")
    wait_state(B, "peer_status", lambda s: any(r["endpoint"] == f"{ta['onion']}:50001" and r["source"] == "AUTHENTICATED" for r in s["records"]), name="OverlayAlice")
    assert control(A, "peer_transport", name="OverlayBob", mode="TOR_ONLY")["accepted"]
    assert control(B, "peer_transport", name="OverlayAlice", mode="TOR_ONLY")["accepted"]
    wait_state(A, "peer_status", lambda s: s["online"], timeout=90, name="OverlayBob")
    wait_state(B, "peer_status", lambda s: s["online"], timeout=90, name="OverlayAlice")
    assert control(A, "peer_send", name="OverlayBob", text="Tor-only A to B")["accepted"]
    assert control(B, "peer_send", name="OverlayAlice", text="Tor-only B to A")["accepted"]
    wait_state(B, "peer_status", lambda s: any(m["text"] == "Tor-only A to B" for m in s["messages"]), timeout=90, name="OverlayAlice")
    wait_state(A, "peer_status", lambda s: any(m["text"] == "Tor-only B to A" for m in s["messages"]), timeout=90, name="OverlayBob")
    print(json.dumps({"result": "PASS", "ygg": [sa["ygg"], sb["ygg"]], "tor": [ta["onion"], tb["onion"]]}), flush=True)


if __name__ == "__main__":
    main()
