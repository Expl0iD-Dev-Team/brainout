# Online economy & content (M4) — model and plan

Authoring the economy/content for the self-hosted gamespace `brainout:desktop`.
Track B / Roadmap M4. This captures the **model** (which turned out to be
profile-centric for desktop) and the concrete plan, grounded in the client/server
code and the Anthill content services.

## The key finding: desktop economy is profile-centric

`DesktopEnvironment.storeEnabled()` returns **false** — the IAP (real-money)
**store is disabled on desktop**. So on the desktop client the economy the player
sees is the **profile**: the wallet, level, loadout and inventory, persisted in
the Anthill **profile** service and granted/updated by the game server during
play. The **store** service (currencies/items/tiers) matters for the **Steam /
Android** builds (which enable IAP) and as the price reference.

So M4 splits in two:

| Piece | Lives in | Who fills it | Desktop impact |
|---|---|---|---|
| Wallet / loadout / inventory / level | **profile** service (`account_profiles.payload` JSON) | game server (on join + during play) | **high** — the menu/UserPanel |
| Currencies, store items, tiers, campaigns | **store** service (`dev_store`) | authored | Steam/Android IAP only |

## Currencies (match the client)

The client's currency ids are in `core/Constants.User`:
`gears` (soft), `skillpts` (skill), `nuclear-material` (premium); plus `ru` and
`USD` for IAP tiers. The profile stores balances under `stats` (e.g.
`stats.gears`). Seeded by `deploy/anthill/seed-economy.sh` into `dev_store`.

## The gap to close first: new online players get an EMPTY profile

When a player joins, the server fetches their profile
(`PlayerClient` → `profileService.getMyProfile`). On `notFound` (a brand-new
anonymous account) it calls `newProfile()` → `setProfile(null)` — an **empty**
profile. Offline, by contrast, `setupOfflineProfile()` applies the rich
`bin/server/default-profile.json` (starting loadout + `stats.gears/skillpts/
nuclear-material`). So a new **online** player has no wallet, no loadout — the
menu shows nothing.

**Fix (DONE — first M4 implementation step):** on the online `notFound` branch,
apply a **starter profile** instead of a blank one (mirrors offline).

1. Authored [`bin/server/starter-profile.json`](../bin/server/starter-profile.json)
   — a clean starting kit (NOT the test `default-profile.json`, which carried
   debug stats like `durability-of-weapon-mp5: 4.997399` and `name: "fefe"`):
   `level 1`, `layout-2`, the default loadout (mp5 / makarov / knife / smoke /
   green skin), `items {sl-grn-smoke:1}`, empty `trophies`/`limits`.
2. `PlayerClient.setupStarterProfile()` loads it on the online `notFound` branch
   (falling back to a blank profile if the file is missing) and marks it dirty so
   it persists to the profile service on first write.

**Starting kit (chosen — "generous"):** `gears 5000`, `skillpts 500`,
`nuclear-material 100` — enough to immediately try upgrades/unlocks and exercise
the menu/store UI. Tune the amounts in `starter-profile.json` to rebalance.

Built (`server:dist`) and restaged into the deployment; a regression spawn still
reaches `SPAWNED`. The profile is applied when a player **connects** to the game
server (kryonet), so populated-profile verification rides on the GUI client join.

## The diff-baseline bug (found by the E2E run)

The first live run applied the starter profile correctly — the client HUD showed
the full kit — but `dev_profile.account_profiles` ended up holding only the
session's changes (`items`, a few `slots`, `badges`), with **no `stats`, `level`
or loadout**. On the next login the profile would then *exist*, so the starter
path would never run again and the player would be left with no wallet.

Cause: `ClientProfile.doSave()` uploads
`JSONDiff.Diff(profile, ext)` — a diff against `profile`, which is assumed to be
what the backend already holds. For a locally-seeded starter profile the backend
holds *nothing*, so every starter field looked "already saved" and was skipped.

Fix: `ClientProfile.resetDiffBaseline()` clears that baseline, and
`PlayerClient.setupStarterProfile()` calls it before marking the profile dirty —
so the first flush uploads the profile in full. Verified: the stored payload now
carries `stats` (5000/500/100), `level`, `slots`, `layout`, `trophies`, `limits`.

## Store catalog — seeded

`seed-economy.sh` now authors a starter catalog in `dev_store`: a `default`
category, one `tier-small` tier, and three items in the `main` store.

**Priced in in-game currency, not IAP.** No payment provider is wired up, so
items carry an `offline-price` in `item_public_data`; `StoreMenu.purchaseOffline()`
spends the profile wallet directly and needs no store component. (A tier row is
still required — `items.item_tier` is NOT NULL with an FK.)

The two payloads are different contracts:

| Field | Consumed by | Shape |
|---|---|---|
| `item_public_data` | the client UI | `title`/`description` per language (`"EN"` is `Environment.getDefaultLanguage()`), `image`, `offline-price:{currency,amount}` |
| `item_private_data` | the server on a paid order | `ServerRewards`: `{"actions":[{"action":…,"id":…,"amount":…}]}` — applied by `PlayerClient.applyOrderContents` |

Action types (`Reward.ActionType`): `unlock` (grant content), `unlockStat`,
`addstat` (add a currency/stat), `addbattlepoints`.

Seeded items: *Crate of Gears* (100 `skillpts` → `addstat gears 1000`),
*Nuclear Material* (2000 `gears` → `addstat nuclear-material 50`), and
*TOZ-34* (1500 `gears` → `unlock sl-pri-toz34`).

**Android shows the store, desktop does not.** `ClientEnvironment.storeEnabled()`
defaults to **true**; `DesktopEnvironment` overrides it to false, while
`AndroidEnvironment` does not — so the catalog is visible in the Android build.
`AndroidEnvironment.getStoreName()` now returns `"main"` (it inherited `null`,
so the client asked for no store at all and the menu stayed empty).

Two traps worth remembering, both fixed in the script:

- **Encoding.** Cyrillic titles must go through `SET NAMES utf8mb4` — the mysql
  client flag alone was not enough, and without it the API served mojibake
  (`Ð¯…`, UTF-8 bytes stored as latin1).
- **Idempotency.** `tiers` has only a *non-unique* KEY on
  `(gamespace_id, store_id, tier_name)`, so `ON DUPLICATE KEY UPDATE` never
  fires and repeat runs piled up duplicate tiers until `SET @tier` failed with
  *"Subquery returns more than 1 row"*. The script now inserts the tier only
  `WHERE NOT EXISTS`. The seed also no longer swallows SQL errors — that is how
  the duplicate-tier failure stayed invisible for several runs.

Verified via the store service: `GET /store/main` returns all three items with
correct Cyrillic and the right `offline-price` values, and repeat seed runs are
clean.

## Verification

- Currencies/store: query `dev_store` (seed script prints them) or the store
  service API.
- Profile: after the starter-profile wiring, a fresh online player's profile in
  `dev_profile.account_profiles` should carry the starting `stats`/`slots`; the
  client UserPanel shows the wallet/loadout instead of empty.

## Status

- [x] Currencies (`gears`/`skillpts`/`nuclear-material`/`ru`/`USD`) + `main`
      store seeded (`deploy/anthill/seed-economy.sh`).
- [x] Decide the new-player starting kit — **generous** (5000/500/100).
- [x] Author `starter-profile.json`; wire the online `notFound` branch to it
      (`PlayerClient.setupStarterProfile`).
- [x] Rebuild `server:dist`, restage the deployment (regression spawn OK).
- [x] **Verified end-to-end with the real client**: a fresh player connects,
      the server logs `Player has no profile! Applying starter profile.`, the
      client HUD shows **5000 gears / 500 skillpts / 100 nuclear** with the
      MP5 + Makarov + smoke + knife loadout, and `dev_profile.account_profiles`
      persists the full profile (`stats`, `level`, `slots`, `layout`, …).
- [x] Fixed the persistence bug this surfaced (see below).
- [ ] Store catalog (items/tiers/prices) for Steam/Android IAP.
