"""Expose ADB-forwarded loopback ports to Android emulators during interop tests."""

from __future__ import annotations

import argparse
import selectors
import socket
import socketserver
import threading


class _ProxyHandler(socketserver.BaseRequestHandler):
    upstream: tuple[str, int]

    def handle(self) -> None:
        with socket.create_connection(self.upstream, timeout=10) as upstream:
            self.request.setblocking(False)
            upstream.setblocking(False)
            selector = selectors.DefaultSelector()
            selector.register(self.request, selectors.EVENT_READ, upstream)
            selector.register(upstream, selectors.EVENT_READ, self.request)
            while True:
                ready = selector.select(timeout=30)
                if not ready:
                    continue
                for key, _ in ready:
                    data = key.fileobj.recv(256 * 1024)
                    if not data:
                        return
                    key.data.sendall(data)


class _ThreadingServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--map",
        action="append",
        required=True,
        metavar="LISTEN_PORT:HOST:PORT",
    )
    args = parser.parse_args()
    servers: list[_ThreadingServer] = []
    for mapping in args.map:
        listen, host, port = mapping.split(":", 2)
        handler = type(
            f"ProxyTo{host}_{port}",
            (_ProxyHandler,),
            {"upstream": (host, int(port))},
        )
        server = _ThreadingServer(("0.0.0.0", int(listen)), handler)
        servers.append(server)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Event().wait()


if __name__ == "__main__":
    main()
