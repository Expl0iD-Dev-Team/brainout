# Brain/Out offline server — deployment

Runs a self-hosted Brain/Out server with **no Anthill backend** (`--offline`).
Core gameplay and dailies work; online events / Battle Pass do not (no
EventService). The Android client connects directly to it (see "Connecting"
below).

## What it needs

Both run methods need these files in `bin/server/` (this directory):

| File | How to produce |
|---|---|
| `brainout-server.jar` | `./gradlew server:dist` (from the repo root) |
| `packages/*.zip` | `./gradlew make_data` (from the repo root) |
| `server-free.json`, `freeplay-maps.shuffle`, ... | tracked in the repo |
| `maps/*.map` | tracked in the repo — the `.map` building blocks for freeplay/lobby/duel |

The jar and `packages/` are git-ignored build artifacts, so build them once
before deploying. The `maps/` directory **must** ship with the server — without
it the server returns HTTP 500 on `/map` and clients fail with
"error while downloading map".

## Ports

Open these on the host / firewall (and forward them if behind NAT):

| Port | Proto | Role |
|---|---|---|
| 36555 | TCP | KryoNet TCP |
| 36556 | UDP | KryoNet UDP |
| 36557 | TCP | HTTP |

## Run with Docker (recommended for a server)

```bash
cd bin/server
docker build -t brainout-server:offline .
docker run --rm \
  -p 36555:36555/tcp -p 36556:36556/udp -p 36557:36557/tcp \
  brainout-server:offline
# or, with compose:
docker compose up --build -d
```

## Run plain (Java 17 on the host)

```bash
cd bin/server
./run-offline.sh
# Windows: run.bat --mode free --settings server-free.json --map freeplay-maps.shuffle --offline
```

## Other modes

Override the command (Docker) or pass different args (plain):

```
--mode free   --settings server-free.json  --map freeplay-maps.shuffle   # freeplay (default)
--mode lobby  --settings server-lobby.json                               # lobby
--mode duel   --settings server-duel.json                                # duel
```

## Connecting from the Android client

The client needs `host;tcp;udp;http` base64-encoded. Two ways:

1. **In-game**: launch the app → on the intro screen tap **Direct Connect** (or
   the offline "Enter server IP" prompt) → type the server's reachable IP. It
   uses the default ports `36555/36556/36557`.
2. **Deep link** `brainout://<base64 of host;tcp;udp;http>`:

   ```bash
   HOST=203.0.113.10
   printf '%s;36555;36556;36557' "$HOST" | base64
   # -> open  brainout://<that base64>  on the device
   adb shell am start -a android.intent.action.VIEW -d "brainout://$(printf '%s;36555;36556;36557' "$HOST" | base64)"
   ```

Notes:
- From an Android **emulator** the host machine is `10.0.2.2`, not `localhost`.
- From a **real device**, use the server machine's LAN/public IP.
- The client allows cleartext traffic, so plain HTTP on 36557 works on Android 9+.
