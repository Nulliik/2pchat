import asyncio
from typing import AsyncIterator, Tuple

from .transport_base import Transport

# Safe fixed backlog value that does not require reading /proc/net/somaxconn.
# Android's SELinux policy (API 34+) denies that read for untrusted apps, so
# relying on the OS default (which internally reads somaxconn) triggers an AVC
# denial.  128 is the POSIX-minimum guaranteed value and is sufficient for P2P
# traffic.
_SERVER_BACKLOG = 128


def _create_socks_connection(
    proxy_host: str,
    proxy_port: int,
    target_host: str,
    target_port: int,
    timeout: float = 30.0,
):
    try:
        import socks
    except ImportError as exc:
        raise ConnectionError("PySocks is required to connect to .onion addresses") from exc
    s = socks.socksocket()
    s.set_proxy(socks.SOCKS5, proxy_host, proxy_port, rdns=True)
    s.settimeout(timeout)
    try:
        s.connect((target_host, target_port))
    except Exception as exc:
        s.close()
        raise ConnectionError(f"Failed to connect to {target_host}:{target_port} via SOCKS5: {exc}") from exc
    s.setblocking(False)
    return s


class DirectTransport(Transport):
    """IPv4/IPv6 direct transport using asyncio streams."""

    ACCEPT_QUEUE_SIZE = 64

    async def connect(
        self, host: str, port: int, **options
    ) -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        clean_host = host.strip().strip("[]")
        if clean_host.lower().endswith(".onion"):
            proxy_host = options.get("proxy_host")
            proxy_port = options.get("proxy_port")
            if not proxy_host or not proxy_port:
                try:
                    from messenger.discovery_bridge import get_proxy_configuration
                    proxy_cfg = get_proxy_configuration()
                    proxy_host = proxy_host or proxy_cfg.get("host") or "127.0.0.1"
                    proxy_port = proxy_port or proxy_cfg.get("port") or 9050
                except Exception:
                    proxy_host = proxy_host or "127.0.0.1"
                    proxy_port = proxy_port or 9050
            sock = await asyncio.to_thread(
                _create_socks_connection, proxy_host, proxy_port, clean_host, port
            )
            return await asyncio.open_connection(sock=sock)
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

        # Explicit backlog avoids the implicit /proc/net/somaxconn read that
        # asyncio performs when backlog is omitted (default=100 on CPython but
        # the kernel call path triggers an SELinux denial on Android 17).
        server = await asyncio.start_server(_handler, host, port, backlog=_SERVER_BACKLOG)

        try:
            async with server:
                while True:
                    reader, writer = await queue.get()
                    yield reader, writer
        finally:
            # Drain any connections that arrived after the consumer stopped
            # so their sockets are not left unclosed.
            while not queue.empty():
                _reader, writer = queue.get_nowait()
                writer.close()
                try:
                    await writer.wait_closed()
                except (ConnectionError, OSError):
                    pass
