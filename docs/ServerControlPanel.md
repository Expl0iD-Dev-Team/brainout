# Server Control Panel — Development Plan

A web admin panel to control the self-hosted Brain/Out server: pick an
**event/preset** (which fixes the game mode, packages and rules), then a
**map**, and (re)start the server with that configuration. Shows live status
(container up/down, current preset/map, players online).

## Decisions (resolved in design review)

| Topic | Decision |
|---|---|
| Control model | **Orchestrator** — restart is acceptable; no live-switch requirement |
| What "event" means | **Thematic preset** = named bundle (mode + settings/packages + defines + map-set) |
| Composition | **Hierarchical**: event → mode (fixed by preset) → map (from the preset's set) |
| How the server is (re)started | **Docker API** — recreate the game container with new `command` |
| Presets / maps source | **Curated catalog** (read existing `server-*.json` / `*-set.json` / `maps/`), read-only for MVP |
| Auth | **Single admin password/token + HTTPS** |
| Stack | **Python FastAPI** backend + lightweight frontend (Jinja+htmx or small SPA) |
| Instances | **One server** (one game container), architecture leaves room for more |
| Status shown | Container state + current config + **players online + current map** |
| Live-data source | **New `GET /status` endpoint** added to the game server (`ContentHttpServer`) |
| Map within a preset | **Single map per launch** (operator picks the exact map that runs); set-rotation is a later option |

## Background (how the server works — relevant facts)

- A server config = **mode** (`--mode`, `GameMode.ID`: normal/domination/deathmatch/
  ctf/assault/lobby/free/foxhunt/gungame/zombie/duel/editor) + a **settings file**
  (`server-*.json`: ports, `packages`, `defines`) + a **map or map-set**
  (`maps-set.json` named maps each declaring their packages/defines; `*.shuffle`
  for freeplay; or a single `maps/*.map`).
- Mode + packages (events) are **launch arguments** — changing them needs a
  restart. Maps within a running game-phase server *can* be changed live via the
  console (`switchmap`), but that is out of MVP scope.
- The server has a console command system (`Console` + `ServerInput` reads stdin):
  `switchmap`, `shuffle`, `endgame`, `kick`, `shutdown`, … (future: live control).
- The HTTP content server (`ContentHttpServer`, port 36557) already serves `/map`;
  a `/status` endpoint will be added there.

## Architecture

```
┌────────────────────────┐      Docker socket      ┌──────────────────────┐
│  Panel (FastAPI)       │ ─── recreate container ─▶│  Game server         │
│  - catalog (presets)   │                          │  (Docker container)  │
│  - auth (1 password)   │ ─── GET :36557/status ──▶│  --mode --settings   │
│  - REST/JSON API       │ ─── docker logs tail ───▶│  --map --offline     │
│  - serves frontend     │                          └──────────────────────┘
└────────────────────────┘
        ▲ HTTPS
        │
   Operator (browser / phone)
```

- **Panel backend (FastAPI)**: reads the curated catalog; talks to Docker
  (`docker-py` / mounted `/var/run/docker.sock`) to recreate the game container
  with the chosen `command`; polls the game server `/status`; tails container
  logs; single-password auth (session/JWT); serves the frontend + JSON API.
- **Panel frontend**: pick preset → pick map (filtered to the preset) → **Apply**
  (restart); Start/Stop/Restart; status widget (up/down, current preset/map,
  players); optional log tail.
- **Game server change**: add `GET /status` → JSON `{mode, currentMap, players[],
  count}` to `ContentHttpServer`.
- **Deployment**: panel as a second service in compose, with the Docker socket
  mounted; HTTPS via a reverse proxy (Caddy/nginx) or FastAPI behind TLS.

## Data model (catalog)

```
Preset {
  id, name, mode: GameMode.ID,
  settingsFile: "server-free.json",         # packages + defines + ports
  mapSource: "freeplay-maps.shuffle" | "maps-set.json" | single,
  maps: [ Map ],                            # allowed maps for this preset
  defines?: {...}                           # optional overrides
}
Map { id, file: "maps/factory.map", name, thumbnail? }
```

Seed catalog from existing files: **Freeplay** (`server-free.json` +
`freeplay-maps.shuffle`), **Standard rotation** (`server-settings.json` +
`maps-set.json`), **Zombie/Halloween** (`zombie-set.json`), **Lobby**, **Duel**.

## Panel API (backend)

| Method | Path | Purpose |
|---|---|---|
| POST | `/login` | single-password → session/JWT |
| GET | `/catalog` | presets + their maps |
| GET | `/status` | container up/down, current config, players, current map |
| POST | `/apply` | `{presetId, mapId}` → recreate game container |
| POST | `/start` `/stop` `/restart` | lifecycle |
| GET | `/logs` | container log tail |

## Phases

- **Phase 0 — Scaffold.** FastAPI app, Docker client wiring, read the curated
  catalog from the existing config files, single-password auth, serve a stub UI.
- **Phase 1 — MVP control.** Pick preset + map; **Apply** = recreate the game
  container with `--mode/--settings/--map --offline`; Start/Stop; basic container
  status (up/down) + log tail.
- **Phase 2 — Live status.** Add `GET /status` to the game server (Java) + rebuild
  image; panel polls it → players online + current map.
- **Phase 3 — Forced map + deploy.** Make a specific starting map reliable
  (generate a one-map set or `switchmap` right after start); ship the panel as a
  compose service with the Docker socket + HTTPS reverse proxy.
- **Phase 4 — Later.** Editable presets (CRUD), map/package upload, multiple
  concurrent servers (port management), live console control (switchmap/shuffle
  without restart), scheduled events.

## Risks / open implementation points

- **Single-map start vs coupled packages.** A single map needs its packages; in
  `maps-set.json` packages are declared per-map. Forcing one specific starting
  map may require generating a valid one-entry map-set (mounted as a volume) or
  using `switchmap` after the set loads. Decide in Phase 3.
- **Docker socket = root-equivalent.** The panel must stay behind auth and never
  be exposed unauthenticated; consider a least-privilege Docker proxy.
- **/status endpoint** needs a server rebuild and the http port reachable from
  the panel (internal network — fine).
- **HTTPS/cert** for the panel (reverse proxy with Let's Encrypt or self-signed
  for LAN).
