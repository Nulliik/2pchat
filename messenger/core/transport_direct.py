import asyncio
from typing import AsyncIterator, Tuple

from .transport_base import Transport


class DirectTransport(Transport):
    """IPv4/IPv6 direct transport using asyncio streams."""

    ACCEPT_QUEUE_SIZE = 64

    async def connect(
        self, host: str, port: int
    ) -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        return await asyncio.open_connection(host, port)

    async def listen(
        self, host: str, port: int
    ) -> AsyncIterator[Tuple[asyncio.StreamReader, asyncio.StreamWriter]]:
        queue: asyncio.Queue[Tuple[asyncio.StreamReader, asyncio.StreamWriter]] = asyncio.Queue(
            maxsize=self.ACCEPT_QUEUE_SIZE
        )

        async def _handler(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
            try:
                queue.put_nowait((reader, writer))
            except asyncio.QueueFull:
                # Do not let a connection flood retain an unbounded number of
                # sockets while the consumer is busy handshaking.
                writer.close()
                try:
                    await writer.wait_closed()
                except (ConnectionError, OSError):
                    pass

        server = await asyncio.start_server(_handler, host, port)

        try:
            async with server:
                while True:
                    reader, writer = await queue.get()
                    yield reader, writer
        finally:
            while not queue.empty():
                _reader, writer = queue.get_nowait()
                writer.close()
