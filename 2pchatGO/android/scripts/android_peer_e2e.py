"""Two-emulator peer regression using only the isolated groupqa debug package.

Build with -PgroupQaApplicationId=com.example.twopchat.groupqa and install on both
emulators. This resets that test package. A loopback tracker supplies only the
current rendezvous namespace, so legacy hash compatibility cannot hide failures.
"""
import base64
import hashlib
import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote_to_bytes, urlsplit

from android_group_e2e import PACKAGE, adb, control


def info_hash(name, code=None):
    return hashlib.sha1(f"2pchat-rendezvous-v1:{name.lower()}:{code or name}".encode()).digest()


def wait_peer(serial, name, predicate, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = control(serial, "peer_status", name=name)
        if predicate(state):
            return state
        time.sleep(1)
    raise AssertionError(f"Peer state did not converge: {state}")


def main():
    a, b = "emulator-5554", "emulator-5556"
    peers = {}
    requests = []

    class Tracker(BaseHTTPRequestHandler):
        def do_GET(self):
            params = dict(item.split("=", 1) for item in urlsplit(self.path).query.split("&") if "=" in item)
            key = unquote_to_bytes(params.get("info_hash", ""))
            requests.append((key, time.monotonic()))
            port = peers.get(key)
            packed = socket.inet_aton("10.0.2.2") + struct.pack("!H", port) if port else b""
            body = b"d8:intervali60e5:peers" + str(len(packed)).encode() + b":" + packed + b"e"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Tracker)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        for serial in (a, b):
            adb(serial, "shell", "pm", "clear", PACKAGE)
            adb(serial, "shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
            adb(serial, "shell", "monkey", "-p", PACKAGE, "1")
        ia = control(a, "setup", name="PeerAlice")
        ib = control(b, "setup", name="PeerBob")
        assert len(base64.b64decode(ia["fingerprint"], validate=True)) == 32
        assert len(base64.b64decode(ib["fingerprint"], validate=True)) == 32
        adb(a, "forward", "tcp:55154", f"tcp:{ia['port']}")
        adb(b, "forward", "tcp:55156", f"tcp:{ib['port']}")
        peers[info_hash(ia["fingerprint"])] = 55154
        peers[info_hash(ib["fingerprint"])] = 55156
        peers[info_hash("PeerAlice", ia["code"])] = 55154
        peers[info_hash("PeerBob", ib["code"])] = 55156
        migration_fp = base64.b64encode(hashlib.sha256(b"peer-e2e-migration").digest()).decode()
        migrated = "8.8.8.8,[200::123]:50001,10.0.2.2:55159," + "b" * 56 + ".onion:50001"
        control(a, "peer_seed", name="MigrationPeer", fingerprint=migration_fp, endpoints=migrated)
        control(a, "peer_seed", name="PeerBob", fingerprint=ib["fingerprint"], endpoints="")
        control(b, "peer_seed", name="PeerAlice", fingerprint=ia["fingerprint"], endpoints="")
        for serial in (a, b):
            control(serial, "peer_tracker", url=f"http://10.0.2.2:{server.server_port}/announce")
        for serial in (a, b):
            adb(serial, "shell", "am", "force-stop", PACKAGE)
        requests.clear()
        for serial in (a, b):
            adb(serial, "shell", "monkey", "-p", PACKAGE, "1")
        control(a, "setup", name="PeerAlice")
        control(b, "setup", name="PeerBob")
        state = wait_peer(a, "MigrationPeer", lambda s: len(s["records"]) == 4)
        assert all(r["success"] == 0 for r in state["records"])
        print("PASS startup migration: native Base64 identity, IPv4/LAN/Ygg/Tor retained without invented success", flush=True)
        wait_peer(a, "PeerBob", lambda s: s["online"])
        wait_peer(b, "PeerAlice", lambda s: s["online"])
        # Prove tracker results entered the store; LAN alone must not pass this.
        wait_peer(a, "PeerBob", lambda s: any(r["endpoint"] == "10.0.2.2:55156" for r in s["records"]))
        wait_peer(b, "PeerAlice", lambda s: any(r["endpoint"] == "10.0.2.2:55154" for r in s["records"]))
        print("PASS automatic current-namespace tracker results stored and peers authenticated", flush=True)
        for sender, receiver, target, source, body in (
            (a, b, "PeerBob", "PeerAlice", "Peer A to B"),
            (b, a, "PeerAlice", "PeerBob", "Peer B to A"),
        ):
            assert control(sender, "peer_send", name=target, text=body)["accepted"]
            wait_peer(receiver, source, lambda s: any(m["text"] == body for m in s["messages"]))
        print("PASS bidirectional peer messages persisted", flush=True)
        for serial, name in ((a, "PeerBob"), (b, "PeerAlice")):
            wait_peer(serial, name, lambda s: any(r["endpoint"].startswith("10.0.2.") and
                      r["endpoint"].endswith(":51001") and r["source"] == "AUTHENTICATED" for r in s["records"]))
        print("PASS authenticated route exchange stored on both peers", flush=True)
        for _ in range(3):
            control(a, "peer_lookup", name="PeerBob")
        for key in peers:
            times = sorted(t for h, t in requests if h == key)
            assert len([t for t in times if t < times[0] + 55]) <= 2, times
        print("PASS tracker interval respected across repeated lookup registration", flush=True)
        adb(b, "shell", "am", "force-stop", PACKAGE)
        wait_peer(a, "PeerBob", lambda s: not s["online"])
        assert control(a, "peer_send", name="PeerBob", text="Queued while offline")["accepted"]
        adb(b, "shell", "monkey", "-p", PACKAGE, "1")
        control(b, "setup", name="PeerBob")
        wait_peer(b, "PeerAlice", lambda s: any(m["text"] == "Queued while offline" for m in s["messages"]), timeout=90)
        print("PASS restart, automatic reconnect and queued message delivery", flush=True)
        state = control(a, "peer_status", name="MigrationPeer")
        assert len(state["records"]) == 4
        print(json.dumps({"tracker_requests": len(requests), "result": "PASS"}), flush=True)
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
