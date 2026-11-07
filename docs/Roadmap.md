# Parallel Roadmap — Control Panel × Online Platform

Coordinates the two efforts so they run in parallel without colliding:

- **Track A — Server Control Panel** → [ServerControlPanel.md](ServerControlPanel.md).
  Ships value **now** for the offline self-hosted server.
- **Track B — Online Platform** → [OnlinePlatform.md](OnlinePlatform.md).
  Long strategic build: accounts, economy, matchmaking via self-hosted Anthill.

(Android client parity work → [AndroidParity.md](AndroidParity.md); its online
items share Track B's HTTP fix.)

## Status — Track B proven locally (M1–M2)

Executed locally with Docker against an upstream `anthill-dev` stack. Runbook +
details in [OnlinePlatformLocal.md](OnlinePlatformLocal.md); spawn tooling in
[../deploy/anthill/](../deploy/anthill/README.md). Verified end-to-end:

- **M1 core**: env → discovery → **login** (anonymous) → menu; `ENV_SERVICE`
  configurable; brainout app/version/gamespace registered; **profile**, **store**
  services up (store FK fixed).
- **M2 online rooms (desktop)**: **game-controller ↔ master** connected; a custom
  controller image (JRE17) spawns the Java dedicated server; a client `POST
  /create` **spawns a live match** (room `SPAWNED`, freeplay loop running); a 2nd
  player **matchmakes/join**s the same room (`players → 2`, per-player keys).
  No server code changes — `BrainOutServer` already speaks the spawn protocol.

Remaining for M2 polish: the **in-game GUI/kryonet connection** from the real
client, and **TLS/staging** (local run is plain HTTP).

**M4 (economy) started** — see [OnlineEconomy.md](OnlineEconomy.md): the desktop
economy is profile-centric (IAP store is desktop-disabled); currencies seeded;
new online players now get an authored **starter profile** (generous kit) instead
of an empty one (`PlayerClient.setupStarterProfile`, built + deployed). Remaining:
verify the populated profile via the GUI join, and author the store catalog for
Steam/Android IAP. M5 not started.

## How they relate (and the conflict to manage)

- **A serves the offline deployment** (works today). **B builds the online
  deployment** (months). Different enough to progress concurrently.
- **They converge:** B's **game-controller spawn** model supersedes A's
  "launch the server" role. To survive that transition, **A is built around a
  pluggable `ServerBackend` interface** from the start:

  ```
  ServerBackend:  apply(preset, map) · status() · start() · stop() · logs()
    ├─ OfflineDockerBackend   (now)   → recreate the game container + /status
    └─ OnlineAnthillBackend   (later) → room config + Anthill admin queries
  ```

  The frontend shell (catalog, status widget, controls) and the API stay; only
  the backend impl swaps. So A starts as an **offline launcher** and becomes the
  **online ops/admin console** without a rewrite.

## Shared / coordination

- Same remote host, Docker, and a **reverse proxy + TLS** (B requires TLS; A
  rides the same proxy).
- **Python/FastAPI** for A; Anthill (Python) + Java game changes for B.
- The **Android HTTP shade fix** (B Phase 3) is the same work AndroidParity needs.
- **Config ownership**: make `ENV_SERVICE` configurable in B; let A own/edit the
  deploy config (presets, endpoint) so there is one source of truth.

## Interleaved milestones

| Milestone | Track A (Panel) | Track B (Online) | Ships |
|---|---|---|---|
| **M1** | Phase 0–1: pick preset/map/mode, restart offline server, basic status | Phase 1 (start): Anthill core (env/discovery/login/profile) + DB + TLS on staging; make `ENV_SERVICE` configurable | Usable offline admin **+** persistent accounts (desktop, staging) |
| **M2** | Phase 2: `/status` endpoint + live players/map | Phase 2: game-controller + host agent; desktop online matchmaking on staging | Full offline panel **+** online rooms (desktop) |
| **M3** | Phase 3: deploy panel as a compose service + TLS; introduce the `ServerBackend` abstraction | Phase 3: Android HTTP shade + online Android build; Android plays online (staging) | Deployed panel **+** Android online |
| **M4** | Evolve into the **online ops console** (rooms/players/moderation via Anthill admin APIs) | Phase 4: economy/services (store/market/leaderboard/social/event) + content authoring | Online economy (authored) |
| **M5** | Economy admin in the panel (catalog/events) | Phase 5: Battle Pass, promo, report, parity polish | Online parity (self-hosted) |

## Build order within "parallel"

- **Two people:** A and B run truly concurrently; sync at each milestone on the
  shared items (TLS/proxy, `ENV_SERVICE` config, the HTTP shade).
- **Solo:** alternate sprints so A keeps shipping while B's foundation grows —
  A(MVP) → B(core) → A(status) → B(server-online) → A(deploy+abstraction) →
  B(android+economy). A always lands something usable each loop.

## Risks reconciled

- **Don't gold-plate A's offline launcher** — it is transitional for the online
  model, but it is genuinely useful for offline play now and its UI shell +
  `ServerBackend` abstraction carry over. Keep A lean and pluggable.
- **B is the long pole** (~15 microservices + DB + content authoring, months of
  work + ongoing ops). A delivers continuous value meanwhile.
- **One TLS/proxy and one deploy config** shared by both — agree the layout early
  to avoid rework.
- **Economy is authored, not official 1:1** (see OnlinePlatform.md).
