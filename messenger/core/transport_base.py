import asyncio
from typing import AsyncIterator, Tuple


class Transport:
    """Base transport abstraction for all connection types."""

    async def connect(
        self, host: str, port: int
    ) -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        """Connect to a remote host and return reader/writer pair."""
        raise NotImplementedError

    async def listen(
        self, host: str, port: int
    ) -> AsyncIterator[Tuple[asyncio.StreamReader, asyncio.StreamWriter]]:
        """Listen for incoming connections and yield reader/writer pairs."""
        raise NotImplementedError
