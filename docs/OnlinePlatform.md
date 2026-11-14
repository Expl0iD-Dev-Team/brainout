# Self-Hosted Online Platform — Analysis & Plan

Goal: replace the `--offline` self-hosted deployment with a **self-hosted Anthill
backend** so the online platform (accounts, persistent progression, store,
clans, leaderboards, Battle Pass, events) works — including on Android. Centered
on **changing the server-launch mechanism** to the online (controller-spawned)
model.

## How the online platform works today (analysis)

- Entry point is **hardcoded**: `Version.ENV_SERVICE = "https://env.brainout.org"`
  (used by both client and server).
- Bootstrap: **Environment** → returns a **Discovery** location → Discovery
  returns URLs for **13 services** (`Constants.Connection.DISCOVER`):
  `Login, Profile, Promo, Event, Game, Leaderboard, Message, Store, Social,
  Static, Report, Blog, Market`.
- **The game server is itself a platform client**: `AnthillRuntime.Create(ENV_SERVICE)`
  — it is spawned/managed by the **GameService (game controller)**, validates
  player login tokens via **LoginService**, and persists progression via
  **ProfileService**. `--offline` short-circuits all of this (hence "admin since
  online is disabled").
- **Client** (`CSOnlineInit`): `EnvironmentService.getEnvironmentInfo` →
  `DiscoveryService.discoverServices` → `LoginService` auth; then store / clan /
  leaderboard / event services drive the menus (all gated by
  `BrainOut.OnlineEnabled()` / `Env.storeEnabled()`).
- **Anthill** is a separate open-source backend platform
  (`github.com/anthill-platform`) — Python/Tornado microservices + MySQL + Redis.
  Versions here: `anthill-runtime-java 0.2.10` (client), `-server 0.2.0`.
- **Android blocker**: the runtime's HTTP goes through `unirest-java 1.4.9` +
  Apache HttpClient, which collides with Android's stripped framework
  `org.apache.http` (`NoSuchFieldError: AllowAllHostnameVerifier.INSTANCE`).
- **Content/economy data** (store catalog, prices, events, Battle Pass) lives in
  the backend databases, **not in this repo**. Item/weapon/skin *definitions*
  exist in the client content packages (IDs), but the catalog/economy is
  backend-authored.

## Decisions (design review)

| Topic | Decision |
|---|---|
| Scope | **Full parity** (all 13 services + economy) |
| Backend | **Deploy upstream Anthill** (`anthill-platform`, version compatible with runtime 0.2.x) + MySQL/Redis |
| Economy/content data | Official 1:1 is **not feasible** (proprietary, not in repo). Capture **our own client's** responses from the official backend as reference for formats + partial seed; **author the rest** ourselves |
| Server-launch model | **Game-controller spawn** — servers spawned on demand by Anthill's GameService; replaces manual / web-panel launch |
| Android HTTP fix | **Shade/relocate** `org.apache.http` (+ unirest) for the Android build |
| Identity | **Anonymous device accounts** (ANDROID_ID) + **optional** login/password later |

## Target architecture

```
ENV_SERVICE (self-hosted, HTTPS) ─▶ Discovery ─▶ 13 Anthill services
                                                   │
   Client (desktop/Android) ───────────────────────┤ login/profile/store/...
                                                   │
   Game-controller (GameService) ── spawns ──▶ game-server processes (online, no --offline)
        ▲ host agent on the host                    │ validate tokens, persist profiles
```

- **Repoint `ENV_SERVICE`** to the self-hosted environment service. Make it
  **configurable** (build/runtime property) instead of hardcoded.
- **Game servers are spawned by the controller**, not launched by hand. A **host
  agent** on the machine launches `brainout-server.jar` (online mode) per room,
  with mode/map from the room config. This **supersedes** the manual/Docker/web
  -panel launch (the web control panel from `ServerControlPanel.md` becomes a
  backend ops/admin tool, or is retired for online).
- TLS is required (the runtime uses `https`) — reverse proxy + certs.

## The server-launch change (core of this task)

| | Offline (today) | Online (target) |
|---|---|---|
| Who starts the server | human / Docker / web panel | **Anthill game-controller** (host agent) |
| Command | `--mode X --map Y --offline` | online mode, args from room config (no `--offline`) |
| Lifecycle | long-running, manual | spawned per room/matchmaking, ephemeral |
| Registration | none (`--offline`) | registers with controller; validates tokens; persists profiles |

Work: deploy the controller + host agent; define a **server-spawn template**
(image/command/port-range) in the controller; run the server image without
`--offline`; verify a matchmade room spawns a server and players join.

## Components to deploy (Docker compose)

- **Anthill services**: environment, discovery, login, profile, store, market,
  leaderboard, social, event, message, report, blog, promo, static, **game
  (controller)** + **host agent**.
- **Datastores**: MySQL (service schemas), Redis.
- **Reverse proxy + TLS** for the HTTPS endpoints.
- **Game-server image** (existing) launched by the host agent.

## Android changes

- **Gradle shadow**: relocate `org.apache.http` (+ `unirest`) to a shaded package
  for the `android` module → fixes the `NoSuchFieldError`.
- **Online build/config**: `offline = false` and `ENV_SERVICE` pointed at the
  self-hosted env (configurable, not hardcoded). Keep the offline build too.
- The online menus (store/clan/leaderboard/Battle Pass) already live in
  `:client`, only gated by `OnlineEnabled()` — they light up once online.
- **Identity**: anonymous device account (ANDROID_ID); optional login/password UI
  later. Voice/chat parity tracked in `AndroidParity.md` (separate).

## Phases

1. **Backend core up.** Deploy Anthill env + discovery + login + profile + MySQL/
   Redis + TLS; make `ENV_SERVICE` configurable and repoint it. *Goal:* a client
   does env→discovery→login→profile (persistent accounts/progression).
2. **Server online + spawn.** Deploy the game-controller + host agent; run the
   game server in online mode, spawned by the controller; matchmaking/rooms work
   on desktop.
3. **Android online.** Shade the HttpClient; produce an online Android build
   (offline=false, configurable endpoint); verify Android reaches the backend and
   plays an online match.
4. **Economy & services.** Bring up store, market, leaderboard, social, event,
   blog, promo, message, report; author/seed the catalog/prices/events; re-enable
   and test those menus.
5. **Parity polish.** Battle Pass, daily/event content, reporting; reconcile the
   web panel role; tuning and ops hardening.

## Risks / reality check

- **Magnitude**: ~15 microservices + databases + content authoring → a
  multi-month effort and ongoing ops, not a config tweak.
- **Content/economy is not 1:1 with official** — the official data is proprietary
  and not in the repo; the self-hosted economy is **authored by us**.
- **Anthill version compatibility**: deploy the `anthill-platform` backend version
  compatible with `anthill-runtime-java 0.2.10` / `-server 0.2.0`.
- **Legal/ToS**: harvesting the official backend beyond your own client's
  responses is risky — stay within data your own client legitimately receives,
  and author the rest.
- **TLS** is mandatory (runtime uses https) — certs for env/discovery/services.
- **Web control panel conflict**: the controller-spawn model supersedes the
  panel's "launch the server" role — reconcile (panel → backend ops).
- **Security**: a public auth/login backend is an attack surface; needs hardening.
