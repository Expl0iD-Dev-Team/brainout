"""Curated catalog of presets (events) and the maps each one offers.

Read-only for the MVP: derived from the server config files that ship in
bin/server (server-*.json, *-set.json). See docs/ServerControlPanel.md.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from .config import settings


@dataclass
class MapEntry:
    id: str
    name: str
    file: str                         # e.g. "maps/factory.map"
    packages: list[str] = field(default_factory=list)
    defines: dict = field(default_factory=dict)


@dataclass
class Preset:
    id: str
    name: str
    settings_file: str
    mode: str | None = None           # None -> server default (normal)
    map_source: str | None = None     # set/shuffle file passed to --map(s)
    # "set" maps are individually selectable; others run as-is
    source_kind: str = "none"         # set | shuffle | single | none


# Confident, known-good invocations (mirrors bin/server run configs).
PRESETS: list[Preset] = [
    Preset("freeplay", "Freeplay", "server-free.json", mode="free",
           map_source="freeplay-maps.shuffle", source_kind="shuffle"),
    Preset("standard", "Standard rotation", "server-settings.json", mode=None,
           map_source="maps-set.json", source_kind="set"),
    Preset("lobby", "Lobby", "server-lobby.json", mode="lobby", source_kind="none"),
    Preset("duel", "Duel", "server-duel.json", mode="duel", source_kind="single"),
]

_BY_ID = {p.id: p for p in PRESETS}


def list_presets() -> list[Preset]:
    return PRESETS


def get_preset(preset_id: str) -> Preset | None:
    return _BY_ID.get(preset_id)


def _read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def list_maps(preset: Preset) -> list[MapEntry]:
    """Selectable maps for a preset (only meaningful for 'set' sources)."""
    if preset.source_kind != "set" or not preset.map_source:
        return []
    path = settings.server_dir / preset.map_source
    if not path.exists():
        return []
    data = _read_json(path)
    maps = data.get("maps", {})
    result: list[MapEntry] = []
    for key, entry in maps.items():
        result.append(MapEntry(
            id=key,
            name=key.replace("_", " ").replace("-", " ").title(),
            file=entry.get("map", ""),
            packages=list(entry.get("packages", [])),
            defines=dict(entry.get("defines", {})),
        ))
    result.sort(key=lambda m: m.name)
    return result


def build_command(preset: Preset, map_id: str | None) -> tuple[list[str], dict[str, str]]:
    """Build the server launch args for a preset + optional single map.

    Returns (args, mounts) where mounts maps a host file path -> container path
    to bind-mount (used to inject a generated one-map set file).
    """
    args: list[str] = []
    if preset.mode:
        args += ["--mode", preset.mode]
    args += ["--settings", preset.settings_file]

    mounts: dict[str, str] = {}

    if preset.source_kind == "set" and map_id:
        # MVP "single map": generate a one-entry map-set from the chosen map and
        # pass it instead of the full rotation.
        gen_name = "_panel_mapset.json"
        settings.generated_dir.mkdir(parents=True, exist_ok=True)
        host_path = settings.generated_dir / gen_name
        _write_single_map_set(preset, map_id, host_path)
        args += ["--maps", gen_name]
        mounts[str(host_path)] = f"/app/{gen_name}"
    elif preset.map_source:
        args += ["--map", preset.map_source]

    args += list(settings.extra_args)
    return args, mounts


def _write_single_map_set(preset: Preset, map_id: str, dest: Path) -> None:
    src = settings.server_dir / preset.map_source
    data = _read_json(src)
    entry = data.get("maps", {}).get(map_id)
    if entry is None:
        raise ValueError(f"Map '{map_id}' not in {preset.map_source}")
    one = {"rotary": "random", "class": data.get("class", "source.MapSetSource"),
           "maps": {map_id: entry}}
    dest.write_text(json.dumps(one, indent=2), encoding="utf-8")
