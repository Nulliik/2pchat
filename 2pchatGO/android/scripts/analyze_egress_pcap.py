#!/usr/bin/env python3
"""
Egress PCAP Analyzer for 2PChat Tor Strict Mode (SEC-08 / G-03).
Analyzes tcpdump PCAP files and socket logs to verify zero clearnet leaks under Tor Strict mode.
"""

import sys
import struct
import socket
import argparse
from typing import Set, List, Tuple

def parse_pcap_packets(pcap_path: str) -> List[dict]:
    packets = []
    with open(pcap_path, 'rb') as f:
        global_header = f.read(24)
        if len(global_header) < 24:
            return packets
        magic_bytes = global_header[:4]
        if magic_bytes == b'\xa1\xb2\xc3\xd4':
            is_big_endian = True
            _, _, _, _, _, _, network = struct.unpack('>IHHIIII', global_header)
        elif magic_bytes == b'\xd4\xc3\xb2\xa1':
            is_big_endian = False
            _, _, _, _, _, _, network = struct.unpack('<IHHIIII', global_header)
        else:
            raise ValueError(f"Unknown pcap magic bytes: {magic_bytes.hex()}")

        pkt_hdr_fmt = '>IIII' if is_big_endian else '<IIII'
        link_type = network # 1 = DLT_EN10MB (Ethernet), 113 = DLT_LINUX_SLL (cooked), 276 = DLT_LINUX_SLL2

        while True:
            pkt_hdr = f.read(16)
            if len(pkt_hdr) < 16:
                break
            ts_sec, ts_usec, caplen, origlen = struct.unpack(pkt_hdr_fmt, pkt_hdr)
            data = f.read(caplen)
            if len(data) < caplen:
                break

            ip_data = None
            if link_type == 1: # Ethernet
                if len(data) >= 14:
                    eth_type = struct.unpack('>H', data[12:14])[0]
                    if eth_type == 0x0800: # IPv4
                        ip_data = data[14:]
            elif link_type in (113, 276): # Linux cooked
                if len(data) >= 16:
                    proto = struct.unpack('>H', data[14:16])[0] if link_type == 113 else struct.unpack('>H', data[0:2])[0]
                    if proto == 0x0800:
                        ip_data = data[16:] if link_type == 113 else data[20:]
            else:
                if len(data) >= 20 and (data[0] >> 4) == 4:
                    ip_data = data

            if not ip_data or len(ip_data) < 20:
                continue

            version_ihl = ip_data[0]
            if (version_ihl >> 4) != 4:
                continue
            ihl = (version_ihl & 0x0f) * 4
            proto = ip_data[9]
            src_ip = socket.inet_ntoa(ip_data[12:16])
            dst_ip = socket.inet_ntoa(ip_data[16:20])

            transport_data = ip_data[ihl:]
            src_port = 0
            dst_port = 0
            proto_name = "OTHER"

            if proto == 6 and len(transport_data) >= 4: # TCP
                proto_name = "TCP"
                src_port, dst_port = struct.unpack('>HH', transport_data[0:4])
            elif proto == 17 and len(transport_data) >= 4: # UDP
                proto_name = "UDP"
                src_port, dst_port = struct.unpack('>HH', transport_data[0:4])

            packets.append({
                'ts': ts_sec + ts_usec / 1e6,
                'proto': proto_name,
                'src_ip': src_ip,
                'src_port': src_port,
                'dst_ip': dst_ip,
                'dst_port': dst_port,
            })
    return packets

def parse_guard_ips(orconn_status_file: str) -> Set[str]:
    guards = set()
    if not orconn_status_file:
        return guards
    try:
        with open(orconn_status_file, 'r', encoding='utf-8') as f:
            for line in f:
                parts = line.strip().split()
                if parts and ('CONNECTED' in parts or '$' in parts[0]):
                    for token in parts:
                        if ':' in token:
                            host = token.split(':')[0]
                            try:
                                socket.inet_aton(host)
                                guards.add(host)
                            except OSError:
                                pass
    except Exception as e:
        print(f"Warning: could not parse orconn status: {e}", file=sys.stderr)
    return guards

def analyze(pcap_path: str, mode: str, guard_ips: Set[str]) -> Tuple[bool, List[str]]:
    packets = parse_pcap_packets(pcap_path)
    violations = []
    observed_leaks = []

    for pkt in packets:
        dst_ip = pkt['dst_ip']
        dst_port = pkt['dst_port']
        proto = pkt['proto']

        # Loopback traffic (e.g. local SOCKS5 proxy or daemon IPC) is always allowed
        if dst_ip.startswith('127.'):
            continue

        # Check DNS leaks (UDP/TCP 53)
        if dst_port == 53:
            msg = f"DNS leak detected: {proto} to {dst_ip}:53"
            observed_leaks.append(msg)
            if mode == 'strict':
                violations.append(msg)

        # Check STUN / WebRTC leaks (UDP 3478)
        if dst_port in (3478, 19302):
            msg = f"STUN/WebRTC leak detected: UDP to {dst_ip}:{dst_port}"
            observed_leaks.append(msg)
            if mode == 'strict':
                violations.append(msg)

        # Check mDNS / SSDP broadcast leaks (UDP 5353, 1900)
        if dst_port in (5353, 1900):
            msg = f"Local discovery broadcast leak: UDP to {dst_ip}:{dst_port}"
            observed_leaks.append(msg)
            if mode == 'strict':
                violations.append(msg)

        # Direct P2P traffic to port 50001
        if dst_port == 50001:
            msg = f"Direct clearnet P2P traffic: {proto} to {dst_ip}:50001"
            observed_leaks.append(msg)
            if mode == 'strict':
                violations.append(msg)

        # In Strict mode, any external TCP connection MUST be to a verified Tor Guard
        if mode == 'strict' and proto == 'TCP':
            if guard_ips and dst_ip not in guard_ips:
                msg = f"Unauthorized external TCP connection to non-guard IP: {dst_ip}:{dst_port}"
                violations.append(msg)

    if mode == 'strict':
        passed = len(violations) == 0
        return passed, violations
    else: # speed mode
        # Control run in Speed mode MUST observe leaks (mDNS/STUN/direct P2P) to validate test harness sensitivity
        passed = len(observed_leaks) > 0
        return passed, observed_leaks

def main():
    parser = argparse.ArgumentParser(description="Analyze egress PCAP for 2PChat Tor Strict Mode")
    parser.add_argument("--pcap", required=True, help="Path to pcap file")
    parser.add_argument("--mode", required=True, choices=["strict", "speed"], help="Operating mode tested")
    parser.add_argument("--orconn", default="", help="Path to Tor orconn-status dump")
    args = parser.parse_args()

    guard_ips = parse_guard_ips(args.orconn) if args.orconn else set()
    passed, findings = analyze(args.pcap, args.mode, guard_ips)

    print(f"=== Egress Leak Analysis Report (Mode: {args.mode.upper()}) ===")
    print(f"PCAP file: {args.pcap}")
    print(f"Known Tor Guard IPs ({len(guard_ips)}): {', '.join(sorted(guard_ips)) if guard_ips else 'None provided'}")
    print(f"Status: {'PASS' if passed else 'FAIL'}")

    if findings:
        print(f"Findings ({len(findings)}):")
        for f in findings[:20]:
            print(f"  - {f}")
        if len(findings) > 20:
            print(f"  ... and {len(findings) - 20} more")
    else:
        print("Findings: None (Clean isolation)")

    sys.exit(0 if passed else 1)

if __name__ == '__main__':
    main()
