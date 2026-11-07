# Brain/Out Server Control Panel

Web admin panel to pick an **event/preset** + **map** and (re)start the
self-hosted Brain/Out server via Docker, with live status and logs. Track A of
[../docs/Roadmap.md](../docs/Roadmap.md); design in
[../docs/ServerControlPanel.md](../docs/ServerControlPanel.md).

Server control goes through a pluggable `ServerBackend` (`app/backend/`) so the
online (Anthill) backend can replace the offline Docker one later without
touching the UI/API.

## Status (MVP — Phase 0–1)

- Single-password auth (signed session cookie).
- Curated catalog of presets (Freeplay / Standard rotation / Lobby / Duel) read
  from `bin/server/*.json`; map selection for set-based presets.
- `OfflineDockerBackend`: **Apply** recreates the game container with the chosen
  launch command; Start / Stop; container status; logs tail.
- Live players / current map: optional, via the game `/status` endpoint
  (Phase 2 — set `PANEL_STATUS_URL`).

## Run (local, Windows)

```bat
cd panel
setup.bat                  REM one-time: venv + deps
copy .env.example .env     REM then edit .env (password, secret)
run.bat                    REM serves on http://localhost:8080
```

Linux/macOS:

```bash
cd panel
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # edit it
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

## Configuration

All settings are env vars prefixed `PANEL_` (see `.env.example`): admin password,
secret key, the game container name/image/ports, the path to the server config
dir, and the optional status URL.

## How "Apply" works

The offline backend recreates the `PANEL_GAME_CONTAINER` from `PANEL_GAME_IMAGE`
with a launch command built from the preset (`--mode/--settings/--map`). For a
set-based preset with a specific map chosen (MVP "single map"), it generates a
one-entry map-set file and bind-mounts it into the container.

Requires access to the Docker daemon (mount `/var/run/docker.sock` when the panel
itself runs in a container). **The Docker socket is root-equivalent — keep the
panel behind auth and never expose it unauthenticated.**

## Deploy (Docker)

The panel runs as its own container and **manages the game container itself**
(creates/recreates `brainout-server`). So the game server is not a compose
service alongside the panel — the panel owns its lifecycle. Use either the
panel *or* `bin/server/`'s standalone compose for a given server, not both.

```bash
cd panel
cp .env.example .env          # set PANEL_ADMIN_PASSWORD + PANEL_SECRET_KEY
docker compose up --build -d
```

Notes for the host:
- The panel mounts `/var/run/docker.sock` (root-equivalent) — keep it behind a
  **TLS reverse proxy** (Caddy/nginx) and never expose it unauthenticated.
- `../bin/server` is mounted read-only for the catalog; the game image
  `brainout-server:offline` must already be built (see `bin/server/DEPLOY.md`).
- Live status uses `host.docker.internal:36557` (the game server's published
  http port). Adjust `PANEL_STATUS_URL` if your networking differs.
- Generated one-map sets are written to `PANEL_GENERATED_HOST` (a host path
  mounted at the same path inside the panel) so they can be bind-mounted into
  the game container.

## Roadmap

- Phase 2 (done): `/status` endpoint in the game server → live players/map.
- Phase 3 (done): deploy as a container; `ServerBackend` abstraction in place.
- Later: online (Anthill) backend (`OnlineAnthillBackend`), editable presets,
  multiple servers, live console commands.
