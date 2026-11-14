"""Offline backend: manage the game server as a Docker container.

`apply()` recreates the game container with a new launch command (preset + map).
Status/logs come from the Docker API; live players/current map (Phase 2) are an
optional fetch from the game server's /status endpoint.
"""
from __future__ import annotations

import json
import urllib.request

import docker
from docker.errors import NotFound

from .. import catalog
from ..config import settings
from .base import ServerStatus


class OfflineDockerBackend:
    def __init__(self) -> None:
        self._client = docker.from_env()

    # ------------------------------------------------------------------ helpers
    def _container(self):
        try:
            return self._client.containers.get(settings.game_container)
        except NotFound:
            return None

    def _ports(self) -> dict:
        return {
            f"{settings.tcp_port}/tcp": settings.tcp_port,
            f"{settings.udp_port}/udp": settings.udp_port,
            f"{settings.http_port}/tcp": settings.http_port,
        }

    # -------------------------------------------------------------------- public
    def apply(self, preset_id: str, map_id: str | None) -> None:
        preset = catalog.get_preset(preset_id)
        if preset is None:
            raise ValueError(f"Unknown preset '{preset_id}'")

        command, mounts = catalog.build_command(preset, map_id)
        volumes = {host: {"bind": cont, "mode": "ro"} for host, cont in mounts.items()}

        existing = self._container()
        if existing is not None:
            existing.remove(force=True)

        self._client.containers.run(
            settings.game_image,
            command=command,
            name=settings.game_container,
            detach=True,
            ports=self._ports(),
            volumes=volumes or None,
            restart_policy={"Name": "unless-stopped"},
        )

    def start(self) -> None:
        c = self._container()
        if c is not None:
            c.start()

    def stop(self) -> None:
        c = self._container()
        if c is not None:
            c.stop()

    def logs(self, tail: int = 200) -> str:
        c = self._container()
        if c is None:
            return "(no container)"
        return c.logs(tail=tail).decode("utf-8", errors="replace")

    def status(self) -> ServerStatus:
        c = self._container()
        if c is None:
            return ServerStatus(running=False, detail="container not created")

        running = c.status == "running"
        cmd = (c.attrs.get("Args") or [])
        preset_id, mode, mp = _parse_command(cmd)

        st = ServerStatus(
            running=running, preset_id=preset_id, mode=mode, map=mp,
            detail=c.status,
        )

        if running and settings.status_url:
            _fill_live_status(st)
        return st


def _parse_command(args: list[str]) -> tuple[str | None, str | None, str | None]:
    """Best-effort: infer preset/mode/map from the container launch args."""
    mode = mp = settings_file = None
    for i in range(len(args)):
        a = args[i]
        nxt = args[i + 1] if i + 1 < len(args) else None
        if a == "--mode":
            mode = nxt
        elif a == "--settings":
            settings_file = nxt
        elif a in ("--map", "--maps", "-m"):
            mp = nxt
    preset_id = None
    for p in catalog.list_presets():
        if p.settings_file == settings_file and (p.mode == mode or (p.mode is None and mode is None)):
            preset_id = p.id
            break
    return preset_id, mode, mp


def _fill_live_status(st: ServerStatus) -> None:
    try:
        with urllib.request.urlopen(f"{settings.status_url}/status", timeout=2) as r:
            data = json.loads(r.read().decode("utf-8"))
        st.current_map = data.get("currentMap") or data.get("map")
        st.players = list(data.get("players", []))
        st.player_count = data.get("count", len(st.players))
    except Exception:
        # Phase 2 endpoint not present / unreachable — keep Docker-only status.
        pass
