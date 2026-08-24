import asyncio
import ipaddress
import socket
import struct
from typing import AsyncIterator, Optional, Tuple

from .transport_base import Transport
from .transport_direct import DirectTransport


def find_free_port(preferred_port: int = 9053, host: str = "127.0.0.1") -> int:
    """Check if preferred_port is free; if not, request an available ephemeral port from the OS."""
    if preferred_port > 0:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind((host, preferred_port))
                return s.getsockname()[1]
        except OSError:
            pass
    # Request a free port from the OS
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((host, 0))
        return s.getsockname()[1]


class YggdrasilTransport(Transport):
    """Wrapper transport that supports both SOCKS5 Proxy Mode (default) and Direct VPN Mode for IPv6."""

    def __init__(
        self,
        *,
        mode: str = "proxy",
        proxy_host: str = "127.0.0.1",
        proxy_port: Optional[int] = None,
    ) -> None:
        self.mode = mode
        self.proxy_host = proxy_host
        self.proxy_port = proxy_port if proxy_port is not None else 9053
        self._direct = DirectTransport()

    def _validate_ipv6(self, host: str) -> ipaddress.IPv6Address:
        clean_host = host.strip("[]")
        try:
            address = ipaddress.IPv6Address(clean_host)
        except ValueError as exc:
            raise ValueError(f"Yggdrasil transport expects a valid IPv6 address, got {host}") from exc
        if address not in ipaddress.IPv6Network("200::/7"):
            raise ValueError(f"Yggdrasil transport expects an address in 200::/7, got {host}")
        return address

    async def _socks5_connect(
        self, target_ip: ipaddress.IPv6Address, port: int
    ) -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        reader, writer = await asyncio.open_connection(self.proxy_host, self.proxy_port)
        try:
            # 1. Version identifier / method selection message: VER=5, NMETHODS=1, METHOD=0 (No Auth)
            writer.write(b"\x05\x01\x00")
            await writer.drain()

            auth_resp = await reader.readexactly(2)
            if auth_resp[0] != 5 or auth_resp[1] != 0:
                raise ConnectionError(f"SOCKS5 auth negotiation failed: {auth_resp.hex()}")

            # 2. SOCKS5 request: VER=5, CMD=1 (CONNECT), RSV=0, ATYP=4 (IPv6)
            req = bytearray([5, 1, 0, 4])
            req.extend(target_ip.packed)
            req.extend(struct.pack("!H", port))
            writer.write(req)
            await writer.drain()

            # 3. SOCKS5 reply
            resp_hdr = await reader.readexactly(4)
            ver, rep, _, atyp = resp_hdr[0], resp_hdr[1], resp_hdr[2], resp_hdr[3]
            if ver != 5 or rep != 0:
                raise ConnectionError(f"SOCKS5 connection rejected with code {rep}")

            # Drain remaining address bytes from reply
            if atyp == 1:  # IPv4
                await reader.readexactly(6)  # 4 bytes IP + 2 bytes port
            elif atyp == 3:  # Domain
                domain_len = (await reader.readexactly(1))[0]
                await reader.readexactly(domain_len + 2)
            elif atyp == 4:  # IPv6
                await reader.readexactly(18)  # 16 bytes IP + 2 bytes port

            return reader, writer
        except Exception:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
            raise

    async def connect(
        self, host: str, port: int
    ) -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        ip_obj = self._validate_ipv6(host)
        if self.mode == "proxy":
            return await self._socks5_connect(ip_obj, port)
        return await self._direct.connect(str(ip_obj), port)

    async def listen(
        self, host: str, port: int
    ) -> AsyncIterator[Tuple[asyncio.StreamReader, asyncio.StreamWriter]]:
        clean_host = host.strip("[]")
        if clean_host != "127.0.0.1" and clean_host != "0.0.0.0" and clean_host != "::":
            self._validate_ipv6(host)
        async for conn in self._direct.listen(clean_host, port):
            yield conn
