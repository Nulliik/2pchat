#!/usr/bin/env python3
"""
Unit tests for analyze_egress_pcap.py.
Tests detection of DNS, STUN, mDNS, and non-guard TCP leaks in synthetic PCAPs.
"""

import os
import struct
import tempfile
import unittest
from analyze_egress_pcap import analyze, parse_pcap_packets

def build_synthetic_pcap(packets: list) -> bytes:
    # PCAP Global Header: magic 0xa1b2c3d4, major 2, minor 4, tz 0, sigfigs 0, snaplen 65535, network 1 (Ethernet)
    global_hdr = struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1)
    buf = bytearray(global_hdr)

    for pkt in packets:
        src_ip = pkt.get('src_ip', '10.0.2.15')
        dst_ip = pkt['dst_ip']
        src_port = pkt.get('src_port', 12345)
        dst_port = pkt['dst_port']
        proto = pkt.get('proto', 'UDP') # 'UDP' or 'TCP'

        # Ethernet header (14 bytes): dst mac (6), src mac (6), ethertype 0x0800 (2)
        eth_hdr = b'\x00' * 12 + struct.pack('>H', 0x0800)

        # IP header (20 bytes)
        proto_num = 6 if proto == 'TCP' else 17
        src_bytes = bytes(map(int, src_ip.split('.')))
        dst_bytes = bytes(map(int, dst_ip.split('.')))
        ip_len = 20 + 8 # IP header + 8 bytes transport
        ip_hdr = struct.pack('>BBHHHBBH4s4s',
            0x45, 0, ip_len, 0, 0, 64, proto_num, 0, src_bytes, dst_bytes
        )

        # Transport header (8 bytes)
        trans_hdr = struct.pack('>HH', src_port, dst_port) + b'\x00' * 4

        raw_frame = eth_hdr + ip_hdr + trans_hdr
        caplen = len(raw_frame)

        # PCAP Packet Header: ts_sec, ts_usec, caplen, origlen
        pkt_hdr = struct.pack('<IIII', 1700000000, 0, caplen, caplen)
        buf.extend(pkt_hdr)
        buf.extend(raw_frame)

    return bytes(buf)

class TestEgressAnalyzer(unittest.TestCase):

    def setUp(self):
        self.temp_file = tempfile.NamedTemporaryFile(suffix='.pcap', delete=False)
        self.temp_path = self.temp_file.name
        self.temp_file.close()

    def tearDown(self):
        if os.path.exists(self.temp_path):
            os.remove(self.temp_path)

    def test_strict_mode_clean_passes(self):
        # Only loopback and allowed Tor guard
        data = build_synthetic_pcap([
            {'dst_ip': '127.0.0.1', 'dst_port': 9050, 'proto': 'TCP'},
            {'dst_ip': '198.51.100.1', 'dst_port': 9001, 'proto': 'TCP'}, # Guard IP
        ])
        with open(self.temp_path, 'wb') as f:
            f.write(data)

        passed, findings = analyze(self.temp_path, mode='strict', guard_ips={'198.51.100.1'})
        self.assertTrue(passed)
        self.assertEqual(len(findings), 0)

    def test_strict_mode_detects_dns_leak(self):
        data = build_synthetic_pcap([
            {'dst_ip': '8.8.8.8', 'dst_port': 53, 'proto': 'UDP'},
        ])
        with open(self.temp_path, 'wb') as f:
            f.write(data)

        passed, findings = analyze(self.temp_path, mode='strict', guard_ips={'198.51.100.1'})
        self.assertFalse(passed)
        self.assertTrue(any('DNS leak' in f for f in findings))

    def test_strict_mode_detects_stun_leak(self):
        data = build_synthetic_pcap([
            {'dst_ip': '192.0.2.1', 'dst_port': 3478, 'proto': 'UDP'},
        ])
        with open(self.temp_path, 'wb') as f:
            f.write(data)

        passed, findings = analyze(self.temp_path, mode='strict', guard_ips={'198.51.100.1'})
        self.assertFalse(passed)
        self.assertTrue(any('STUN' in f for f in findings))

    def test_strict_mode_detects_unauthorized_external_tcp(self):
        data = build_synthetic_pcap([
            {'dst_ip': '203.0.113.50', 'dst_port': 443, 'proto': 'TCP'}, # Not in guard_ips
        ])
        with open(self.temp_path, 'wb') as f:
            f.write(data)

        passed, findings = analyze(self.temp_path, mode='strict', guard_ips={'198.51.100.1'})
        self.assertFalse(passed)
        self.assertTrue(any('non-guard IP' in f for f in findings))

    def test_speed_mode_control_detects_activity(self):
        # In Speed mode, observing direct leaks confirms test harness sensitivity
        data = build_synthetic_pcap([
            {'dst_ip': '8.8.8.8', 'dst_port': 53, 'proto': 'UDP'},
            {'dst_ip': '192.168.1.5', 'dst_port': 50001, 'proto': 'TCP'},
        ])
        with open(self.temp_path, 'wb') as f:
            f.write(data)

        passed, observed = analyze(self.temp_path, mode='speed', guard_ips=set())
        self.assertTrue(passed)
        self.assertGreater(len(observed), 0)

if __name__ == '__main__':
    unittest.main()
