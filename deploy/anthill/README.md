# Brain/Out × Anthill — online match spawning

This directory makes the self-hosted Anthill **game-controller** spawn the
Brain/Out dedicated server as real online matches, on the local `anthill-dev`
stack. It is the core of online-platform Phase 2.

Verified working end-to-end locally: client auth → `POST /create` → master →
controller → Java server spawns, online-inits (server token + discovery),
registers the room → master returns the join location to the client. The room
shows `state=SPAWNED` and the server runs the live freeplay loop.

## Deploy

`deploy.sh` brings the whole backend up on one Linux host and is safe to re-run.
Step-by-step guide (Russian): [docs/ServerSetup.md](../../docs/ServerSetup.md).

```bash
cp deploy/anthill/config.env.example deploy/anthill/config.env   # set PUBLIC_HOST, SERVER_BUNDLE
./deploy/anthill/deploy.sh
./deploy/anthill/check.sh
```

On top of the pinned upstream `anthill-dev` stack it:

- binds every container port to `127.0.0.1` and puts **nginx** in front of the
  services the game uses, with HTTPS on `19501-19519` (`TLS_PORT_OFFSET`). The
  certificate comes from a private CA (`tls.sh`), so no domain is needed; the
  APK is built to trust that CA (`BRAINOUT_SERVER_CA`). nginx sets `X-Real-IP`,
  otherwise Anthill would treat every proxied request as internal.
- generates random passwords for the `dev:root` account (reachable through the
  public login service) and the game server account (`state/secrets.env`), and
  keeps admin scopes out of the player gamespace.
- builds and runs the real `anthill-market` service instead of the store stub.
- registers the game, provisions the `free`/`lobby` game servers, stages the
  deployment and seeds the starter economy.

Spawned game servers get the public HTTPS addresses from the game master;
`run.sh` rewrites them to `http://127.0.0.1:<port>` (`service-rewrite.json`), so
the server never goes out through the router to reach the backend.

`backup.sh` / `restore.sh` dump and load the Anthill databases; `check.sh` is the
health check `deploy.sh` ends with.

## How spawning works (what we reverse-engineered)

The controller (`anthill-game-controller`) runs a spawned server as a child
process: `<deployment>/<binary> <sock> <ports> <arguments…>` with `cwd` = the
extracted deployment dir, injecting per-room env. The brainout server already
speaks this protocol (`BrainOutServer`): argv = sockets + `tcp,udp,http`, env =
`room_settings`, `server_settings`, `room_id`, `game_max_players`, and — minted
by the master — `login_access_token` and `discovery_services`.

The master fills `login_access_token`/`discovery_services` only if the
`game_servers.game_settings` JSON carries a `token` block (it authenticates a
`dev:` account, non-unique, to mint a server-side token) and a `discover` list
(resolved via discovery). See `provision.sh`.

## Two gotchas that cost real time

1. **No Java in the controller** — the stock controller image is Debian buster
   (Python, no JRE). `controller.Dockerfile` adds a pinned Temurin 17 JRE.
2. **`/tmp` is a noexec tmpfs** — the online controller loads a native JZMQ
   `.so` from `java.io.tmpdir`, which fails to map on noexec. The launcher
   (`anthill-run.sh`) points `-Djava.io.tmpdir=/opt/brainout-tmp` (an exec dir
   baked into the image).

## Steps by hand

`deploy.sh` runs these in order; each also works on its own once the stack is up:
`controller.Dockerfile` (controller image with a JRE), `provision.sh` (game
servers + server account, needs `SRV_PASS`), `build-deployment.sh <dir>` (stage
the server build), `seed-economy.sh` (currencies and store catalog).

### Trigger a match

```bash
# anonymous player token (scope game)
TOKEN=$(curl -s -X POST http://localhost:9501/auth \
  --data-urlencode credential=anonymous --data-urlencode username=p1 \
  --data-urlencode key=p1 --data-urlencode scopes=game \
  --data-urlencode gamespace=brainout:desktop)

# create + spawn a room (path is game/server/version)
curl -s -X POST http://localhost:9508/create/brainout/free/valpha2 \
  --data-urlencode "access_token=$TOKEN" --data-urlencode "settings={}"
# -> {"location":{"host":"localhost","ports":[..,..,..]},"settings":{...},"id":"N","key":"..."}
```

`docker exec mysql mysql -uroot -pRoot123 dev_game -e "SELECT room_id,state,players FROM rooms"`
should show the room `SPAWNED`; the per-server log is in the controller at
`/var/log/brainout_free_<room>.log*`.

### Matchmaking join (verified)

A second player can matchmake into the **same** live room:

```bash
curl -s -X POST http://localhost:9508/join/brainout/free/valpha2 \
  --data-urlencode "access_token=$TOKEN2" --data-urlencode "settings={}" \
  --data-urlencode "auto_create=false"
# -> same location/ports as the room, a new slot, a per-player key; rooms.players -> 2
```

Note: a spawned room is reaped if no client actually connects (kryonet) within
the slot-reservation grace period — `create`/`join` only reserve a slot, so do
the join promptly when testing by hand.

## Verified with the real desktop client (and what it taught us)

Running the desktop client online against this stack got as far as:
`Auth success → CSGetRegions → CSFindLobby → CSConnecting → [kryonet] Connecting`.
Two real gaps surfaced and are fixed in `provision.sh`:

1. **The client joins a `lobby` first**, not a match — `CSFindLobby` calls
   `joinGame(…, "lobby", auto_create=true)`. With only the `free` server
   provisioned the client died with `404`.
2. **`--map` is a file path**, not a map name: `MapSource` does
   `Gdx.files.absolute(settings.map)`, so `--map lobby` threw
   `Map 'lobby' was not found`; it must be `maps/lobby.map`. (The `free` server
   works because `freeplay-maps.shuffle` *is* a file in the deployment root.)

### Docker Desktop on Windows blocks the last hop

The lobby server spawns and runs (`Server started`, `Inited: {"status":"OK"}`),
but the Windows client cannot open TCP to it. Measured against one live server:

| From | game port 38009 | service port 9501 |
|---|---|---|
| inside the Docker VM (host-network container) | **open** | — |
| Windows host | **refused** | open |

Only `anthill_game_controller` uses `network_mode: host`; every other service is
bridge-networked with an explicit `ports:` mapping (hence 9501 works). Docker
Desktop does not forward a host-network container's **dynamically allocated**
ports (the 38000-40000 game pool) to Windows. Options: enable Docker Desktop's
host-networking feature, narrow `ports_pool_*` and publish that range from a
bridge-networked controller, or run the stack on real Linux — where
`network_mode: host` behaves natively and the problem disappears.

## Not yet done

- A real `deployments` upload/delivery (we pre-stage into the host-mounted
  binaries dir, so the controller skips the download). For multi-host, wire the
  master's deployment storage + controller download instead.
- The actual in-game connection from the GUI client (kryonet handshake with the
  per-player key). The whole platform side — spawn, register, matchmake, join,
  slots, per-player keys — is proven; the netcode join + economy/content for the
  gamespace are the remaining pieces.
