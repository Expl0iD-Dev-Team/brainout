"""ServerBackend interface.

The panel UI/API talks only to this interface, so the implementation can swap
from the offline Docker launcher to the online Anthill controller later without
touching the rest of the app (see docs/Roadmap.md).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol


@dataclass
class ServerStatus:
    running: bool
    # config the server was launched with (best-effort, from the container command)
    preset_id: str | None = None
    mode: str | None = None
    map: str | None = None
    # live data (filled in Phase 2 via the game /status endpoint)
    current_map: str | None = None
    players: list[str] = field(default_factory=list)
    player_count: int = 0
    detail: str = ""


class ServerBackend(Protocol):
    """A managed game server."""

    def status(self) -> ServerStatus: ...

    def apply(self, preset_id: str, map_id: str | None) -> None:
        """(Re)start the server with the given preset + optional single map."""

    def start(self) -> None: ...

    def stop(self) -> None: ...

    def logs(self, tail: int = 200) -> str: ...
