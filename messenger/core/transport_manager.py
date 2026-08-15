from typing import Callable, Dict

from .transport_base import Transport
from .transport_direct import DirectTransport
from .transport_yggdrasil import YggdrasilTransport
from .transport_yggdrasil_embedded import EmbeddedYggdrasilTransport

TRANSPORT_FACTORIES: Dict[str, Callable[..., Transport]] = {
    "direct": lambda **_: DirectTransport(),
    "tor": lambda **_: DirectTransport(),
    "ygg": lambda **_: YggdrasilTransport(),
    "ygg-embedded": lambda **kwargs: EmbeddedYggdrasilTransport(**kwargs),
}


def get_transport(scheme: str, **options) -> Transport:
    if scheme not in TRANSPORT_FACTORIES:
        raise ValueError(f"Unknown transport scheme: {scheme}")
    return TRANSPORT_FACTORIES[scheme](**options)


async def connect(scheme: str, host: str, port: int, **options):
    transport = get_transport(scheme, **options)
    return await transport.connect(host, port, **options)


async def listen(scheme: str, host: str, port: int, **options):
    transport = get_transport(scheme, **options)
    async for conn in transport.listen(host, port):
        yield conn
