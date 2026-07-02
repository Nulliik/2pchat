import ipaddress
from typing import AsyncIterator, Tuple
import asyncio

from .transport_base import Transport
from .transport_direct import DirectTransport


class YggdrasilTransport(Transport):
    """Wrapper transport that validates IPv6 addresses (including Yggdrasil)."""

    def __init__(self) -> None:
        self._direct = DirectTransport()

    def _validate_ipv6(self, host: str) -> None:
        try:
            ipaddress.IPv6Address(host)
        except ValueError as exc:
            raise ValueError("Yggdrasil transport expects a valid IPv6 address") from exc

    async def connect(
        self, host: str, port: int
    ) -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        self._validate_ipv6(host)
        return await self._direct.connect(host, port)

    async def listen(
        self, host: str, port: int
    ) -> AsyncIterator[Tuple[asyncio.StreamReader, asyncio.StreamWriter]]:
        # Accept on any IPv6 address (Yggdrasil nodes expose IPv6 addresses directly)
        self._validate_ipv6(host)
        async for conn in self._direct.listen(host, port):
            yield conn
