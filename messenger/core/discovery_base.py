from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List


@dataclass(frozen=True)
class PeerEndpoint:
    host: str
    port: int


@dataclass(frozen=True)
class PeerDescriptor:
    version: int
    nickname: str
    identity_fingerprint: str | None
    signing_public_key: str | None
    transport: str
    endpoints: tuple[PeerEndpoint, ...]
    expires_at: int
    sequence: int
    nonce: str
    signature: str | None


class DiscoveryProvider(ABC):
    @abstractmethod
    async def announce(
        self,
        nickname: str,
        shared_code: str,
        *,
        transport: str,
        endpoints: List[PeerEndpoint],
    ) -> PeerDescriptor:
        """Publish or refresh a peer discovery record."""

    @abstractmethod
    async def resolve(
        self,
        nickname: str,
        shared_code: str,
        *,
        expected_fingerprint: str | None = None,
    ) -> List[PeerDescriptor]:
        """Resolve currently advertised peers for the provided rendezvous secret."""

    async def withdraw(self, nickname: str, shared_code: str) -> None:
        """Optionally withdraw a published record."""
