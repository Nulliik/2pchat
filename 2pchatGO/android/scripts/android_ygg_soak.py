"""Yggdrasil-only media soak on provisioned debug emulators; never seeds LAN routes.

Uses existing contacts and media, preserves app data, checks newly received files
by SHA-256 and checks the active transport before and after every transfer.
ADB is only the control/evidence channel. No TCP forwarding is used.
"""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET


class Soak:
    def __init__(self, args):
        self.args = args
        self.directory = Path(args.output)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.events = self.directory.joinpath("results.jsonl").open("a", encoding="utf-8")

    def record(self, **event):
        event["timestamp"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        line = json.dumps(event, ensure_ascii=False)
        print(line, flush=True)
        self.events.write(line + "\n")
        self.events.flush()

    def adb(self, serial, *args):
        return subprocess.check_output([self.args.adb, "-s", serial, *args],
                                       encoding="utf-8", errors="replace", timeout=30)

    def shell(self, serial, *args):
        return self.adb(serial, "shell", shlex.join(args))

    def logs(self, serial):
        return self.adb(serial, "logcat", "-d", "-v", "epoch", "-s", "2PChatE2E:I", "*:S")

    @staticmethod
    def parse(lines):
        for line in lines.splitlines():
            offset = line.find('{"action":')
            if offset >= 0:
                yield line, json.loads(line[offset:])

    def control(self, serial, action, **extras):
        action = "com.example.twopchat.debug." + action
        before = set(self.logs(serial).splitlines())
        command = ["am", "broadcast", "-n", self.args.package +
                   "/com.example.twopchat.debug.E2EControlReceiver", "-a", action]
        for key, value in extras.items():
            command += ["--es", key, str(value)]
        self.shell(serial, *command)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            for line, result in self.parse(self.logs(serial)):
                if line not in before and result.get("action") == action:
                    if not result.get("ok"):
                        raise RuntimeError(result)
                    return result
            time.sleep(.25)
        raise TimeoutError((serial, action))

    def files(self, serial):
        output = self.shell(serial, "run-as", self.args.package, "find",
                            "files/config/downloads", "-type", "f")
        return set(output.splitlines())

    def digest(self, serial, path):
        return self.shell(serial, "run-as", self.args.package, "sha256sum", path).split()[0]

    def state(self, serial, contact):
        state = self.control(serial, "PEER_STATE", nickname=contact)
        if not state.get("ygg_enabled") or not state.get("online") or state.get("transport") != "Yggdrasil":
            raise AssertionError({k: v for k, v in state.items() if k != "messages"})
        return state

    def audit_mesh(self, serial):
        runtime = ET.fromstring(self.shell(serial, "run-as", self.args.package, "cat",
                                          "shared_prefs/yggdrasil_runtime_ephemeral.xml"))
        peers = json.loads(runtime.find("./string[@name='yggdrasil_runtime_peers_json']").text)
        live = [peer for peer in peers if peer.get("Up")]
        assert live, "No live Yggdrasil uplinks"
        for peer in live:
            assert not peer.get("Inbound"), "Unexpected inbound/local Yggdrasil peering"
            host = urlsplit(peer["URI"]).hostname
            assert host and host.lower() != "localhost", "Local mesh uplink"
            try:
                address = ipaddress.ip_address(host)
            except ValueError:
                # Hostnames are recorded as hostnames, not claimed to be an
                # independently verified IP. No host-side DNS substitution.
                continue
            assert address.is_global, "Private/loopback mesh uplink"
        self.record(status="PASS", check="mesh_uplinks", serial=serial,
                    uris=[peer["URI"] for peer in live])

    def transfer(self, source, target, contact, reverse_contact, number):
        self.state(source, contact)
        self.state(target, reverse_contact)
        self.audit_mesh(source)
        self.audit_mesh(target)
        before_files = self.files(target)
        before_logs = set(self.logs(source).splitlines())
        expected = self.digest(source, "files/" + self.args.filename)
        started = time.monotonic()
        result = self.control(source, "APP_FILE", nickname=contact, filename=self.args.filename)
        assert result.get("requested"), result
        self.record(status="START", iteration=number, source=source, sha256=expected)
        deadline = started + self.args.timeout
        completed = False
        received = None
        while time.monotonic() < deadline:
            for line, event in self.parse(self.logs(source)):
                if (line not in before_logs and event.get("action") == "APP_FILE_RESULT"
                        and event.get("filename") == self.args.filename):
                    if not event.get("completed"):
                        raise AssertionError({"sender_result": event, "iteration": number})
                    completed = True
            if completed:
                for path in self.files(target) - before_files:
                    if self.digest(target, path) == expected:
                        received = path
                        break
            if received:
                break
            time.sleep(2)
        if not received:
            raise TimeoutError({"iteration": number, "sender_completed": completed})
        self.state(source, contact)
        self.state(target, reverse_contact)
        token = f"ygg_soak_{time.time_ns()}"
        self.control(target, "APP_SEND", nickname=reverse_contact, body=token)
        text_deadline = time.monotonic() + 45
        while token not in self.state(source, contact).get("messages", []):
            if time.monotonic() >= text_deadline:
                raise TimeoutError("Return text was not received")
            time.sleep(1)
        self.record(status="PASS", iteration=number, source=source, receiver_path=received,
                    sha256=expected, duration_seconds=round(time.monotonic() - started, 2))

    def run(self):
        a, b = self.args.first, self.args.second
        try:
            for serial, contact in ((a, self.args.first_contact), (b, self.args.second_contact)):
                policy = self.control(serial, "REAPPLY_POLICY")
                assert policy.get("native_applied") and policy.get("ygg_enabled"), policy
                self.record(status="PASS", check="no_lan_policy", serial=serial, flags=12)
            deadline = time.monotonic() + 120
            while True:
                try:
                    self.state(a, self.args.first_contact)
                    self.state(b, self.args.second_contact)
                    break
                except AssertionError:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(2)
            for i in range(self.args.rounds):
                if i % 2:
                    self.transfer(b, a, self.args.second_contact, self.args.first_contact, i + 1)
                else:
                    self.transfer(a, b, self.args.first_contact, self.args.second_contact, i + 1)
                time.sleep(self.args.pause)
            self.record(status="PASS", test="ygg_only_media_soak", rounds=self.args.rounds)
        except Exception as error:
            self.record(status="FAIL", error=str(error))
            raise
        finally:
            for serial in (a, b):
                self.directory.joinpath(serial + ".log").write_text(
                    self.adb(serial, "logcat", "-d"), encoding="utf-8")
            self.events.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--package", default="com.example.twopchat.go")
    parser.add_argument("--first", default="emulator-5554")
    parser.add_argument("--second", default="emulator-5556")
    parser.add_argument("--first-contact", required=True)
    parser.add_argument("--second-contact", required=True)
    parser.add_argument("--filename", choices=["test_video.mp4", "test_image.jpg"], default="test_video.mp4")
    parser.add_argument("--rounds", type=int, default=10)
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--pause", type=float, default=5)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.rounds < 1 or args.timeout <= 0 or args.pause < 0:
        parser.error("rounds and timeout must be positive; pause must be nonnegative")
    Soak(args).run()


if __name__ == "__main__":
    main()
