# Android Port vs Desktop — Feature Parity & Improvement Plan

Comparison of the reactivated Android client (this port) against the desktop
client, and a prioritized plan for the features the Android port is missing.

Method: compared `DesktopEnvironment` / `KeyboardController` / `DesktopSettings`
and the online-gated menus against what the Android port (`AndroidEnvironment`,
`AndroidGameController` twin-stick HUD, `AndroidSettings`) actually exposes.

## A. Online platform — **disabled by design** (offline-only port)

The Android client runs with `offline = true` always (the Anthill backend uses
Apache HttpClient, incompatible with Android's stripped `org.apache.http`).
Everything gated by `BrainOut.OnlineEnabled()` / `Env.storeEnabled()` is
therefore off:

- Accounts / login, matchmaking
- Store & purchases, Battle Pass, daily quests/rewards needing the backend
- Clans (`ClanMenu`, `BrowseClansMenu`), Leaderboards, Friends, Blog/news,
  online events, server-backed cases/containers

**Status:** out of scope for the port. Re-enabling means making the Anthill
runtime Android-compatible (shade/replace Apache HttpClient, port the platform
services) — a very large effort, separate from "missing UX on a working client".

## B. In-game touch-control coverage — **actionable**

The HUD covers: move, 360° aim, fire (auto), reload, weapon-cycle, use/activate,
crouch (sit), run (stick edge). Desktop keyboard actions **not** reachable on
touch:

| Missing action | Desktop key | Notes |
|---|---|---|
| Direct weapon slots 1–4 | 1/2/3/4 | only cycle exists on Android |
| Binoculars / flashlight | B / L | slot-5 / slot-6 |
| Previous slot | Q | quick weapon swap |
| ADS / zoom | C | aim-down-sights |
| Drop weapon / drop ammo | G / F | |
| Unload weapon | T | |
| Firing-mode switch | V | auto/semi |
| Change team | N | |
| Squat | Z | distinct from sit/crouch |
| Hide interface | − | screenshots/clarity |

## C. Communication — **actionable**

- **Text chat / team chat**: no HUD button; needs Android IME text entry.
- **Voice chat**: `VoiceChatManager` + `VoiceChatMsg` exist, but Android lacks
  the `RECORD_AUDIO` permission, a push-to-talk button, and mic-capture wiring.
- **Player list** (desktop TAB → `openPlayerList`): no button on Android.

## D. Menus / UX reachable on touch — **actionable (important)**

- **Pause / Exit / Disconnect menu**: opened by ESC on desktop (`ExitMenu`). On
  Android there is no ESC and **BACK quits the whole app** (libGDX default). So
  you currently cannot disconnect / reach settings / quit cleanly in-game.
- **In-game settings** (sound, graphics quality, language): reachable only via
  menus that ESC/buttons open — needs a touch entry point. (Verify.)
- **Loadout / weapon selection before spawn** (`SpawnMenu`/`PlayerSelectionMenu`):
  confirm it is fully operable by touch.
- **UI scale for phone DPI**: menus/dialogs render desktop-sized (small, centered)
  — cosmetic but affects usability (already noted in AndroidPort.md Phase 6).

## E. Input devices — **actionable, low effort**

- **Gamepad**: `gdx-controllers-android` backend is bundled (added this port) but
  untested — Bluetooth controller play likely works with `GamePadManager`.
- **Hardware/Bluetooth keyboard**: `KeyboardController` exists; could work.

## F. Platform-specific / N/A

Fullscreen/resolution/vSync settings (mobile is always fullscreen), keybind
customization (touch), the map editor (`editor`/`editor2`, mouse-driven), and
Steam integration are not applicable to the touch port.

---

## Prioritized improvement plan

### P1 — core playability gaps
1. **BACK → pause/Exit menu** + an on-screen menu button. Lets you disconnect /
   open settings / quit without killing the app. *(small)*
2. **Weapon slot bar** on the HUD: direct select 1–4 + binoculars/flashlight +
   previous-slot. *(medium)*
3. **ADS/zoom button** (or aim-stick press). *(small)*
4. **Player-list button**. *(small)*

### P2 — communication & secondary actions
5. **Text chat** (Android IME) + chat/team-chat button. *(medium)*
6. **"More actions" panel** (expandable/radial): drop weapon, drop ammo, unload,
   change team, firing-mode, squat, hide-interface. *(medium)*
7. **Voice chat**: `RECORD_AUDIO` permission + push-to-talk button + mic capture
   via `Gdx.audio.newAudioRecorder`. *(medium–large)*

### P3 — polish & devices
8. **UI scale for phone DPI** (menus/dialogs). *(medium)*
9. **Gamepad / Bluetooth controller** test + enable. *(small–medium)*
10. **In-game settings reachable** (sound / graphics quality / language). *(small)*

### Out of scope (by design)
Section A — the entire online platform. Tracked separately; needs the Anthill
backend made Android-compatible first.

## Suggested order

P1 (1→4) makes the client genuinely usable in a match (you can manage weapons,
see players, pause/quit). P2 adds team play (chat/voice) and the long tail of
actions. P3 is polish. Each item is independent; pick per match-testing feedback.
