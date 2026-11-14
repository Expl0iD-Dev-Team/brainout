# Online Platform — Local Anthill Deployment (status & runbook)

Track B, executing locally with Docker. This records the state of the
self-hosted Anthill backend stood up on the dev machine, the fixes applied, the
remaining blockers, and the brainout-side integration still needed.

## What is deployed

Anthill is brought up from the upstream **anthill-dev** compose (not vendored
into this repo — cloned alongside it):

```bash
cd <somewhere outside this repo>
git clone https://github.com/anthill-platform/anthill-dev.git
cd anthill-dev/dev
docker compose pull          # prebuilt anthillplatform/anthill-*:latest images
docker compose up -d
```

Result: infra (mysql 5.7, redis, rabbitmq, influxdb) + ~17/19 services
**running**: environment, discovery, login, profile, admin, leaderboard, message,
social, event, blog, config, dlc, exec, game(master), promo, report, static.

Endpoints (host): environment `:9503`, discovery `:9502`, login `:9501`,
profile `:9512`, admin UI `:9500` (login `root` / `anthill`).

**Good news on compatibility:** a service logged `API version is '0.2'`, which
matches `anthill-runtime-java 0.2.10` used by the game — so the protocol version
lines up.

## Fixes applied to get the stack up

1. **CRLF line endings** — cloning on Windows gave `dev/keys/setup.sh` (and the
   collectd script) CRLF, so the keygen crashed (`syntax error ... expecting
   "then"`). Fixed: `find . -name '*.sh' -not -path './.git/*' -exec sed -i
   's/\r$//' {} +`, then `docker compose build anthill_keygen`.
2. **Keys** — keygen then generated `dev/keys/private/anthill.pem` and
   `dev/keys/public/anthill.pub`; the services that crash-looped on the missing
   keys recovered.

## Backend configured for brainout (done)

Registered the game in the platform DBs and verified the first two online steps:

```sql
-- dev_environment: app + version -> the seed 'dev' environment (discovery :9502)
INSERT INTO applications (application_name, application_title, min_api)
  VALUES ('brainout','Brain/Out','0.2');
INSERT INTO application_versions (application_id, version_name, version_environment)
  SELECT application_id, 'valpha2', 1 FROM applications WHERE application_name='brainout';

-- dev_login: gamespace alias the game asks for -> the seed default gamespace (id 1)
INSERT INTO gamespace_aliases (gamespace_name, gamespace_id) VALUES ('brainout:desktop', 1);
```

The game's `DISCOVER` list requires a `market` service that anthill-dev lacks, so
a **stub** was added to `dev/discovery/discovery-services.json` (pointing `market`
at the store address) and discovery restarted — otherwise multi-discover 404s.

Verified:
- `GET http://localhost:9503/brainout/valpha2` → `{"discovery":"http://localhost:9502"}`
- `GET http://localhost:9502/services/login,market` → 200 (all services resolve)

So **env → discovery** of the online init now succeeds for brainout.

### Login works (anonymous auth) — verified by running the client

Running the desktop client online against the local backend
(`java -Dbrainout.env_service=http://localhost:9503 -jar bin/client/brainout-desktop.jar --unsafe`)
drove the full boot online init. First attempt **looped**, spamming new anonymous
accounts: the login server rejected the auth with
`"User 'anonymous:…' has no scope 'report_upload' asked." result_id:scope_restricted`
— the seed gamespace allowed only
`profile_write,profile,game,message_listen,group,party,event,exec_func_call`,
but the client requests `ClientConstants.Scopes.SCOPES`
(`profile,profile_write,game_root,game_mod,game_editor,message_listen,game,store,group,static_upload,party,party_create,report_upload,blog,game_ban,market`).
The runtime maps `scope_restricted` to `forbidden`, so `CSOnlineInit` kept
creating a new anonymous account and retrying.

Fix — widen the gamespace's allowed scopes to the union of those it requests:

```sql
-- dev_login: gamespace id 1 allowed scopes (must cover ClientConstants.Scopes.SCOPES)
UPDATE gamespace SET gamespace_scopes=
 'profile,profile_write,game,game_root,game_mod,game_editor,message_listen,store,group,static_upload,party,party_create,report_upload,blog,game_ban,market,event,exec_func_call'
 WHERE gamespace_id=1;
```

After this the boot log shows the clean path (no loop):
`onlineInited → Services discovered → Authenticating → Auth done. → CSWaitForUser`.

So **env → discovery → login (anonymous)** now succeeds end-to-end against the
local backend; the client reaches the main-menu wait state with a persisted
anonymous account. **Profile** is fetched only after the first menu interaction
(`UserPanel`), past `CSWaitForUser` — that step needs a click in the game window,
so it isn't exercised by a headless run (profile service is up and `profile`/
`profile_write` scopes are granted, so it's expected to work on first menu entry).

## Store FK crash — fixed

The **store** service crash-looped: `OrdersModel` creates its tables, but
`orders` failed with *"Cannot add foreign key constraint"*, after which a query
hit *"Table 'dev_store.orders' doesn't exist"* and the service restarted.

Root cause: `orders` has `CONSTRAINT orders_ibfk_6 FOREIGN KEY (order_campaign_id)
REFERENCES campaigns (campaign_id)`, but the model creates `orders` **before**
`campaigns`, so the referenced table doesn't exist yet → the FK (and thus the
whole `orders` table) fails to create. `campaigns`/`campaign_items` were absent
from `dev_store` entirely.

Fix — after the first `docker compose up` (so `dev_store` and the other store
tables exist), create the three missing tables in dependency order; the store
uses plain `CREATE TABLE` and tolerates an "already exists" on restart:

```bash
IMG=anthillplatform/anthill-store:latest
SQL=/usr/local/lib/python3.7/site-packages/anthill/store/sql
for f in campaigns campaign_items orders; do
  docker run --rm --entrypoint sh $IMG -c "cat $SQL/$f.sql" \
    | docker exec -i mysql mysql -uroot -pRoot123 dev_store
done
docker restart anthill_store
```

After this the store logs `Model 'OrdersModel' started` … `Service 'store'
started.` and stays up (`RestartCount=0`).

## game_controller ↔ game_master — connected

The controller crash-looped only at first boot: its `MasterConnectionModel`
logged in against login service and got `HTTP 500` a few times
(`RestartCount=7`), then recovered. Current state is healthy:

- controller log ends at `Logged in → MasterConnectionModel started →
  Connected to Game Master` and is otherwise quiet (no reconnect spam).
- `dev_game.regions` has `local` (id 1, default); `dev_game.hosts` has the
  controller as `host_state=ACTIVE` with a **current** `host_heartbeat`. So the
  master sees the controller alive and heartbeating.

So the controller↔master link works. What's left is **provisioning a spawnable
brainout server** (see below), not the connection itself.

## Spawning a brainout match — WORKING (Phase 2)

Online match spawning works end-to-end against the local stack. Tooling and the
full write-up live in [`deploy/anthill/`](../deploy/anthill/README.md); summary:

Flow verified: anonymous player token → `POST :9508/create/brainout/free/valpha2`
→ master picks the local host/controller → controller spawns the Java server
→ server online-inits and registers the room → master returns
`{"location":{"host","ports":[tcp,udp,http]},"key":...}`. The room shows
`state=SPAWNED` in `dev_game.rooms` and the server runs the live freeplay loop.

The brainout server already speaks the controller spawn protocol (argv =
sockets + `tcp,udp,http`; env `room_settings`/`server_settings`/`room_id`/
`game_max_players` + master-minted `login_access_token`/`discovery_services`) —
**no server code changes**. What it took on the backend:

1. **Provisioning** (`deploy/anthill/provision.sh`): `game_servers` (with
   `game_settings.binary=run.sh`, `ports:3`, `arguments`, a `token` block and a
   `discover` list), `game_server_versions`, a `deployments` row
   (`status=delivered`), and `game_deployments.current_deployment`.
2. **Server-side token**: the master mints `login_access_token` by
   authenticating a `dev:brainout_server` account (non-unique) — so that account
   needs `auth_non_unique` + the server scopes (`provision.sh` creates it).
3. **JRE in the controller**: the stock controller image has no Java;
   `deploy/anthill/controller.Dockerfile` adds a Temurin 17 JRE.
4. **noexec /tmp**: the online controller loads a native JZMQ `.so` from
   `java.io.tmpdir`, which fails on the controller's noexec `/tmp` tmpfs. The
   launcher (`anthill-run.sh`) sets `-Djava.io.tmpdir=/opt/brainout-tmp` (baked
   into the image).
5. **Deployment files**: staged into the controller's host-mounted
   `binaries/runtime/brainout/valpha2/1/` (`build-deployment.sh`), so the
   controller runs them locally without a master delivery/download.

## Remaining backend blockers
- **No `market` service**: anthill-dev provides login/profile/store/social/etc.
  but **not** `market`, which the game's `Constants.Connection.DISCOVER`
  requires. Currently **stubbed** in discovery (→ store address) so multi-discover
  resolves; trading/real-estate calls would still fail until a real market is
  provided or `MarketService.ID` is dropped from the client DISCOVER list.

## Brainout-side integration still needed

1. **`ENV_SERVICE` is now configurable** (done): `core/Version.java` reads the
   `brainout.env_service` system property or `BRAINOUT_ENV_SERVICE` env var,
   defaulting to the official endpoint. Point client+server at the local
   environment service, e.g. run with `-Dbrainout.env_service=http://localhost:9503`
   (desktop/server) or `BRAINOUT_ENV_SERVICE=http://<host>:9503`. (Android online
   build would wire this through BuildConfig.)
2. **Configure the game in the platform** via the admin UI: the `brainout`
   application / `brainout:desktop` gamespace, application keys, environment
   discovery location, and seed content (store/economy — authored, see
   OnlinePlatform.md).
3. **Run without `--offline`** and validate env→discovery→login→profile, then the
   rest. Expect to iterate on the `market` gap and store FK first.

## Risk / reality

- Protocol version matches (0.2), which removes the biggest unknown, but full
  game-online still needs: store FK fixed, the market gap resolved, the
  gamespace + economy configured, and `ENV_SERVICE` repointed + rebuild. This is
  a multi-session integration effort.
- The Anthill stack is heavy (~20 containers); `docker compose down` to stop.

## Resume checklist

- [x] Fix store `orders` FK (create campaigns/campaign_items/orders in dep order).
- [x] Resolve the missing `market` service (stubbed in discovery -> store addr).
- [x] `game_controller` → `game_master` connected (host ACTIVE + heartbeating;
      early `HTTP 500` login retries self-recovered).
- [x] Provision + spawn a brainout match online (Phase 2) — `game_servers` +
      version + deployment + server dev token + JRE-in-controller + noexec-/tmp
      fix; room reaches `SPAWNED`. See `deploy/anthill/`.
- [x] Matchmaking join verified — a 2nd player joins the same live room
      (`/join … auto_create=false`), `rooms.players → 2`, per-player keys.
- [ ] In-game GUI client connection (kryonet handshake) + gamespace economy/content.
- [x] Make `ENV_SERVICE` configurable (`-Dbrainout.env_service` / `BRAINOUT_ENV_SERVICE`).
- [x] Register `brainout` app / `valpha2` version / `brainout:desktop` gamespace
      (SQL above); env→discovery verified.
- [x] Widen gamespace allowed scopes to cover `ClientConstants.Scopes.SCOPES`
      (else login loops on `scope_restricted`); SQL above.
- [x] Run client online (no `--offline`); login (anonymous auth) verified —
      reaches `CSWaitForUser` with a persisted account.
- [ ] Verify profile persistence (needs first menu interaction past
      `CSWaitForUser`; profile service up + scopes granted).
- [ ] Seed economy/content for store/events/battlepass.
