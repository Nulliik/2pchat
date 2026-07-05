import asyncio
from typing import AsyncIterator, Tuple

from .transport_base import Transport


class DirectTransport(Transport):
    """IPv4/IPv6 direct transport using asyncio streams."""

    async def connect(
        self, host: str, port: int
    ) -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        return await asyncio.open_connection(host, port)

    async def listen(
        self, host: str, port: int
    ) -> AsyncIterator[Tuple[asyncio.StreamReader, asyncio.StreamWriter]]:
        queue: asyncio.Queue[Tuple[asyncio.StreamReader, asyncio.StreamWriter]] = asyncio.Queue()

        async def _handler(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
            await queue.put((reader, writer))

        server = await asyncio.start_server(_handler, host, port)

        async with server:
            while True:
                reader, writer = await queue.get()
                yield reader, writer
