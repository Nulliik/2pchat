"""Embedded Yggdrasil transport launcher.

This transport starts a local Yggdrasil node (via the `yggdrasil` binary)
and then relies on the existing direct IPv6 transport for connectivity.
It is meant for environments where Yggdrasil is not already running and the
application should manage the process lifecycle.
"""

import asyncio
import json
import os
import tempfile
from pathlib import Path
from typing import AsyncIterator, List, Optional, Tuple

from .transport_base import Transport
from .transport_direct import DirectTransport


class EmbeddedYggdrasilTransport(Transport):
    """Start and reuse a local Yggdrasil daemon before delegating to IPv6."""

    def __init__(
        self,
        *,
        binary_path: str = "yggdrasil",
        config_path: Optional[str] = None,
        public_peers: Optional[List[str]] = None,
        auto_start: bool = True,
    ) -> None:
        self.binary_path = binary_path
        self.config_path = Path(config_path) if config_path else None
        self.public_peers = public_peers or []
        self.auto_start = auto_start
        self._process: Optional[asyncio.subprocess.Process] = None
        self._direct = DirectTransport()

    def _prepare_config(self) -> Path:
        """Return a config path, optionally overlaying public peers.

        The provided config must already be valid JSON (generate via
        `yggdrasil -genconf -json > yggdrasil.conf`). If custom peers are
        provided, a temporary config file is written with the updated peer
        list.
        """

        if not self.config_path:
            raise ValueError(
                "Embedded Yggdrasil transport requires a JSON config file. "
                "Generate one with `yggdrasil -genconf -json > yggdrasil.conf` "
                "and pass --yggdrasil-config to the CLI."
            )

        if not self.config_path.exists():
            raise FileNotFoundError(f"Yggdrasil config not found: {self.config_path}")

        if not self.public_peers:
            return self.config_path

        data = json.loads(self.config_path.read_text())
        data["Peers"] = self.public_peers

        # Write to a temporary file so the base config remains untouched.
        fd, temp_path = tempfile.mkstemp(prefix="ygg-config-", suffix=".json")
        os.close(fd)
        path_obj = Path(temp_path)
        path_obj.write_text(json.dumps(data, indent=2))
        return path_obj

    async def _ensure_running(self) -> None:
        if self._process and self._process.returncode is None:
            return

        config_path = self._prepare_config()
        cmd = [self.binary_path, "-useconffile", str(config_path)]

        try:
            self._process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except FileNotFoundError as exc:
            raise FileNotFoundError(
                f"Unable to start Yggdrasil binary at '{self.binary_path}'. "
                "Ensure the binary is available or supply --yggdrasil-binary."
            ) from exc

    async def connect(
        self, host: str, port: int
    ) -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        if self.auto_start:
            await self._ensure_running()
        return await self._direct.connect(host, port)

    async def listen(
        self, host: str, port: int
    ) -> AsyncIterator[Tuple[asyncio.StreamReader, asyncio.StreamWriter]]:
        if self.auto_start:
            await self._ensure_running()
        async for conn in self._direct.listen(host, port):
            yield conn

    async def stop(self) -> None:
        if self._process and self._process.returncode is None:
            self._process.terminate()
            try:
                await asyncio.wait_for(self._process.wait(), timeout=5)
            except asyncio.TimeoutError:
                self._process.kill()
        self._process = None
