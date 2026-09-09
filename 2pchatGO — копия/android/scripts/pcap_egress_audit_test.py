#!/usr/bin/env python3
"""
P2P Network Egress & PCAP Verification Suite for 2PChat (SEC-08 / G-03).
Synthesizes binary PCAP captures and validates strict isolation and leak detection.
"""

import os
import socket
import struct
import subprocess
import sys
import tempfile
from typing import List, Tuple

def create_pcap_bytes(packets: List[Tuple[str, str, int, str, int, bytes]]) -> bytes:
    """
    Constructs standard binary PCAP file bytes (Ethernet link-layer type DLT_EN10MB).
    Each packet: (proto 'TCP'|'UDP', src_ip, src_port, dst_ip, dst_port, payload_bytes).
    """
    buf = bytearray()
    # PCAP Global Header: magic 0xa1b2c3d4, v2.4, thiszone 0, sigfigs 0, snaplen 65535, network 1 (Ethernet)
    buf.extend(struct.pack('>IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1))

    for idx, (proto, src_ip, src_port, dst_ip, dst_port, payload) in enumerate(packets):
        # Ethernet Header (14 bytes): Dst MAC, Src MAC, EtherType (0x0800 IPv4)
        eth_hdr = b'\x00\x11\x22\x33\x44\x55\x66\x77\x88\x99\xaa\xbb\x08\x00'

        src_ip_bytes = socket.inet_aton(src_ip)
        dst_ip_bytes = socket.inet_aton(dst_ip)

        if proto == 'TCP':
            # TCP Header (20 bytes): src_port, dst_port, seq, ack, data_offset/flags, window, checksum, urgent
            proto_num = 6
            tcp_hdr = struct.pack('>HHIIHHHH', src_port, dst_port, 1000 + idx, 0, (5 << 12) | 0x02, 65535, 0, 0)
            transport_layer = tcp_hdr + payload
        elif proto == 'UDP':
            # UDP Header (8 bytes): src_port, dst_port, length, checksum
            proto_num = 17
            udp_len = 8 + len(payload)
            udp_hdr = struct.pack('>HHHH', src_port, dst_port, udp_len, 0)
            transport_layer = udp_hdr + payload
        else:
            raise ValueError(f"Unsupported protocol: {proto}")

        ip_total_len = 20 + len(transport_layer)
        # IPv4 Header (20 bytes): ver/ihl, dscp/ecn, total_len, id, flags/frag, ttl, proto, checksum, src, dst
        ip_hdr = struct.pack('>BBHHHBBH4s4s',
                             0x45, 0, ip_total_len, 100 + idx, 0x4000, 64, proto_num, 0,
                             src_ip_bytes, dst_ip_bytes)

        frame_data = eth_hdr + ip_hdr + transport_layer
        caplen = len(frame_data)
        ts_sec = 1700000000 + idx
        ts_usec = 0
        # PCAP Packet Header: ts_sec, ts_usec, caplen, origlen
        pkt_hdr = struct.pack('>IIII', ts_sec, ts_usec, caplen, caplen)
        buf.extend(pkt_hdr)
        buf.extend(frame_data)

    return bytes(buf)


def main():
    print("=== Running 2PChat P2P Network Traffic & Tor Egress PCAP Audit ===")
    script_dir = os.path.dirname(os.path.abspath(__file__))
    analyzer_path = os.path.join(script_dir, "analyze_egress_pcap.py")
    assert os.path.isfile(analyzer_path), f"Analyzer not found at {analyzer_path}"

    with tempfile.TemporaryDirectory() as tmpdir:
        guard_ip = "185.220.101.5"
        orconn_file = os.path.join(tmpdir, "orconn-status.txt")
        with open(orconn_file, "w") as f:
            f.write(f"$A1B2C3D4E5F6~TorGuard CONNECTED {guard_ip}:443\n")

        # TEST 1: Strict Mode Clean Capture (Tor SOCKS, Control port, Guard TLS only)
        clean_packets = [
            # Local SOCKS5 handshake (127.0.0.1:9050)
            ('TCP', '127.0.0.1', 54321, '127.0.0.1', 9050, b'\x05\x01\x00'),
            # Local Tor control port query (127.0.0.1:9051)
            ('TCP', '127.0.0.1', 54322, '127.0.0.1', 9051, b'GETINFO orconn-status\r\n'),
            # Outbound TLS to verified Tor Guard (185.220.101.5:443)
            ('TCP', '10.0.2.15', 49152, guard_ip, 443, b'\x16\x03\x01\x00\x10...ClientHello'),
        ]
        clean_pcap = os.path.join(tmpdir, "clean_strict.pcap")
        with open(clean_pcap, "wb") as f:
            f.write(create_pcap_bytes(clean_packets))

        res_clean = subprocess.run(
            [sys.executable, analyzer_path, "--pcap", clean_pcap, "--mode", "strict", "--orconn", orconn_file],
            capture_output=True, text=True
        )
        print("Test 1 (Clean Tor Strict Isolation):", "PASS" if res_clean.returncode == 0 else "FAIL")
        if res_clean.returncode != 0:
            print(res_clean.stdout)
            print(res_clean.stderr)
            sys.exit(1)

        # TEST 2: Strict Mode Injected Leaks (Must detect all leaks and return code 1)
        leaking_packets = [
            # Legitimate local SOCKS traffic
            ('TCP', '127.0.0.1', 54321, '127.0.0.1', 9050, b'\x05\x01\x00'),
            # LEAK 1: Clearnet DNS query (UDP 53)
            ('UDP', '10.0.2.15', 38120, '8.8.8.8', 53, b'\x12\x34\x01\x00\x00\x01...'),
            # LEAK 2: Clearnet STUN binding (UDP 3478)
            ('UDP', '10.0.2.15', 38121, '1.2.3.4', 3478, b'\x00\x01\x00\x08...'),
            # LEAK 3: Direct clearnet P2P traffic (TCP 50001)
            ('TCP', '10.0.2.15', 38122, '93.184.216.34', 50001, b'\x00\x00\x00\x10...'),
            # LEAK 4: Unauthorized TCP connection to non-guard host
            ('TCP', '10.0.2.15', 38123, '142.250.190.46', 80, b'GET / HTTP/1.1\r\n\r\n'),
        ]
        leaking_pcap = os.path.join(tmpdir, "leaking.pcap")
        with open(leaking_pcap, "wb") as f:
            f.write(create_pcap_bytes(leaking_packets))

        res_leak = subprocess.run(
            [sys.executable, analyzer_path, "--pcap", leaking_pcap, "--mode", "strict", "--orconn", orconn_file],
            capture_output=True, text=True
        )
        print("Test 2 (Adversarial Egress Leak Detection):", "PASS" if res_leak.returncode != 0 else "FAIL")
        assert res_leak.returncode != 0, "Analyzer failed to detect injected leaks in strict mode!"
        assert "DNS leak detected" in res_leak.stdout, "Missing DNS leak alert"
        assert "STUN/WebRTC leak detected" in res_leak.stdout, "Missing STUN leak alert"
        assert "Direct clearnet P2P traffic" in res_leak.stdout, "Missing direct P2P leak alert"
        assert "Unauthorized external TCP connection to non-guard IP" in res_leak.stdout, "Missing non-guard TCP alert"
        print("  -> Verified all 4 distinct egress leak classes detected and flagged.")

        # TEST 3: Speed Mode Sensitivity Check (Must observe leaks as expected control run)
        res_speed = subprocess.run(
            [sys.executable, analyzer_path, "--pcap", leaking_pcap, "--mode", "speed", "--orconn", orconn_file],
            capture_output=True, text=True
        )
        print("Test 3 (Speed Mode Control Sensitivity):", "PASS" if res_speed.returncode == 0 else "FAIL")
        assert res_speed.returncode == 0, f"Speed mode sensitivity check failed: {res_speed.stdout}"

    print("=== All P2P Network Traffic & PCAP Egress Tests PASSED ===")

if __name__ == "__main__":
    main()
