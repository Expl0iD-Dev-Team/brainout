# Android Port — Reactivation & Modernization Plan

Goal: reactivate and modernize the Android client so a player can run a
Brain/Out server on an external machine and connect to it from an Android
device.

This document is the working plan and progress tracker for the port. Check off
items as they land.

## Current State (findings)

The Android module exists but is disabled and stale.

- Full module already present under `android/`:
  - `android/src/com/desertkun/brainout/android/AndroidLauncher.java`
  - `android/src/com/desertkun/brainout/BrainOutAndroid.java` (`extends BrainOutClient`)
  - `AndroidEnvironment`, `AndroidSettings`, `AndroidGameController`,
    `AndroidConstants`, `packages/AndroidPackageManager`, `packages/AndroidPackage`
- Touch controls are **partially implemented** in
  `android/src/com/desertkun/brainout/AndroidGameController.java`:
  virtual joystick (`touchpad-move`), fire button (`button-touch-launch`),
  OK/Cancel buttons, and an `aim` mode driven by `AndroidConstants.Touch.*`.
- Server connection flow in the client:
  - `BrainOutClient.ConnectToLocation` (static, set from `--connect` on desktop)
  - decoded by `HashedUrl.unhash()` (base64 of `host;tcp;udp;http`)
  - consumed in `client/.../menu/impl/IntroMenu.java:275` →
    `ClientController.connect(location, tcp, udp, http, ...)`
- Server can run locally with `--offline` (no Anthill backend), listening on
  three ports (default `36555;36556;36557` = tcp/udp/http).

## Main Blockers

| Blocker | Detail | Location |
|---|---|---|
| Module disabled | not in `include` | `settings.gradle` |
| Config commented out | `project(":android")` inside `/* */` | `build.gradle:50-95` |
| Ancient toolchain | AGP `1.0.0`, buildTools `21.1.2`, compileSdk `22` — incompatible with Gradle 8.3 | `build.gradle:16`, `android/build.gradle` |
| libGDX API drift | code targets old gdx (e.g. `scrolled(int)` vs `scrolled(float,float)`) with gdx `1.11.0` | `AndroidGameController.java` |
| No server-address input | Android has no `--connect` CLI args | needs new UI / deep-link |
| Data package signing | client verifies signatures; desktop uses `--unsafe` (no CLI on Android) | `AndroidEnvironment`, `Readme.md:49` |
| Storage model | `WRITE_EXTERNAL_STORAGE` + `getExternalStorageDirectory()` broken on Android 10+ | `AndroidManifest.xml`, `AndroidEnvironment.java:82` |

## Environment Constraints (verification status)

The toolchain/wiring changes below were authored but **could not be built or
verified** in the session where they were written, because that environment
had:

- **no Android SDK** (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset, no `sdkmanager`/`adb`)
- **only JDK 21** (AGP 8 targets JDK 17; the rest of the project expects JDK 11)

Consequently the Android module is wired to be **opt-in**: it is only configured
when an Android SDK is present, or when `-PwithAndroid` is passed. Default
desktop/server builds are unaffected. All Android changes must still be built
and validated on a machine with JDK 17 + Android SDK (platform 34) before they
can be trusted. Open known risks to confirm on first real build:

- libGDX version conflict between the custom `com.github.desertkun.libgdx:gdx`
  fork used by `:core` and the stock `gdx-backend-android:1.11.0`.
- exact AGP 8 task wiring for `copyAndroidNatives` (jniLibs packaging).

## Target Versions

- Android Gradle Plugin: **8.1.x** (wrapper is already Gradle 8.3)
- `compileSdk 34`, `targetSdk 34`, `minSdk 24` (Android 7.0)
- Native ABIs: `armeabi-v7a`, **`arm64-v8a`**, `x86`, `x86_64`
  (arm64 is mandatory for modern devices)

## Phased Plan

### Phase 0 — Setup
- [x] Branch created (`claude/peaceful-mccarthy-rigm7y`)
- [x] Plan committed (this document)
- [ ] Confirm target versions above with maintainers

### Phase 1 — Toolchain & module wiring
> Implemented (UNVERIFIED — no SDK/JDK17 in authoring env). The wiring is
> guarded so it stays inert without an Android SDK.
- [x] `settings.gradle`: include `'android'` conditionally (SDK present or `-PwithAndroid`)
- [x] root `build.gradle`: gated `google()` repo + AGP classpath `8.1.4`
      (only when SDK present); legacy `project(":android")` block left commented
      — config now lives in `android/build.gradle`
- [x] `android/build.gradle`: rewritten for AGP 8 (`namespace`, `compileSdk 34`,
      `defaultConfig{applicationId, minSdk 24, targetSdk 34, versionCode/Name}`,
      `buildTypes`, `packagingOptions` for `.so`, `copyAndroidNatives` task)
- [x] `AndroidManifest.xml`: dropped legacy `package=`/`uses-sdk`, added
      `android:exported="true"`, removed `WRITE_EXTERNAL_STORAGE`
- [x] gdx-backend-android + natives incl. **arm64-v8a** and x86_64
- [x] Checkpoint: `./gradlew android:assembleDebug` **builds a debug APK**
      (verified on JDK 17 + Android SDK platform 34; `android-debug.apk`, ~5 MB,
      assets not yet bundled — see Phase 5)

> **Verified build environment (2026-06):** Microsoft OpenJDK 17, Android SDK
> platform-34 + build-tools 34.0.0 (AGP 8.1.4 auto-pulled build-tools 33.0.1),
> cmdline-tools installed. `local.properties` points `sdk.dir` at the SDK
> (gitignored). Build command:
> `gradlew.bat :android:assembleDebug -PwithAndroid` with `JAVA_HOME`=JDK 17.
> Two packaging conflicts had to be resolved in `android/build.gradle`:
> 1. **libGDX fork vs stock gdx** — `gdx-backend-android` pulls stock
>    `com.badlogicgames.gdx:gdx:1.11.0`, which duplicates the
>    `com.github.desertkun.libgdx:gdx` fork from `:core`/`:client`. Fixed by
>    excluding the stock `gdx` module from `gdx-backend-android`.
> 2. **Duplicate META-INF resources** from transitive jars — fixed with a
>    `packagingOptions.resources.excludes` block.

### Phase 2 — Fix compilation (API drift)
- [x] `AndroidGameController`: updated to gdx 1.11 APIs
      (`scrolled(float,float)`; replaced stale
      `BrainOutClient.EventMgr.sendEvent(receiver, e)` with the inherited
      `sendEvent(e)` helper). `Touchpad`/`Stage`/`InputProcessor` compile clean.
- [x] Reviewed `AndroidPackageManager`/`AndroidPackage`/`AndroidSettings`/
      `AndroidEnvironment` for stale calls against current `:core`/`:client`:
      - `ClientEnvironment` now requires `getStoreComponent()`; the engine owns
        `Reflection` (`BrainOut.R = new Reflection()`), so the old
        `getReflection()`/`ClientReflection` override was removed.
      - `ClientSettings` now takes a `ClientEnvironment` ctor and requires
        `getDefaultDisplayMode()`/`getDisplayModes()` (was `getDefaultWidth/Height`).
        Added `AndroidDisplayMode` (subclass to reach the protected
        `Graphics.DisplayMode` ctor) built from device `DisplayMetrics`.
      - package helpers moved `PackageManager.*` → `ZipContentPackage.packageFile/
        packageFilename`; `ContentPackage` ctor now throws checked
        `ValidationException` (declared on `AndroidPackage`/`createPackage`);
        `PackageFileHandle.entryName` is gone (now stored locally).
      - `AndroidLauncher` wires env→settings(ctx) in the right order.
- [x] `AndroidEnvironment.getUniqueId()`: replaced hidden
      `android.os.SystemProperties` reflection with `Settings.Secure.ANDROID_ID`
- [x] Checkpoint: module compiles and links against `:client` (full debug APK)

### Phase 3 — Modern storage
- [x] `AndroidEnvironment.getExternalPath()` → `context.getFilesDir()`-based
      absolute path (app-private internal storage, permission-free, scoped-storage
      safe). Chose `getFilesDir()` over `getExternalFilesDir(null)` so it shares
      the base that `Gdx.files.local` uses on Android — otherwise mainmenu unpack
      and `PackageManager.searchPackages()` (which scans `Gdx.files.local
      ("packages")`) would resolve to different locations. The only caller is
      `ZipContentPackage`, always with a relative `packages/<name>.zip`.
- [x] `WRITE_EXTERNAL_STORAGE` already absent from the manifest (Phase 1);
      only `INTERNET` is requested.
- [x] mainmenu unpack (`AndroidEnvironment.init()`) and the package cache use
      writable dirs: packages live under `getFilesDir()` (init() `mkdirs()` the
      parent), cache via `context.getCacheDir()`.

### Phase 4 — Server-address entry on Android
> Decision (confirmed): support **both** deep-link and a UI field.
- [x] Deep-link: `brainout://` `intent-filter` in manifest; `AndroidLauncher`
      reads `getIntent().getData()` and sets `BrainOutClient.ConnectToLocation`
      before engine start
- [x] UI "Direct Connect" menu (host + ports) that base64-encodes
      `host;tcp;udp;http` and calls the existing connect path
      — `client/.../menu/impl/DirectConnectMenu.java` (host + tcp/udp/http
      fields, defaults `36555/36556/36557`); encodes via `Base64.encode` then
      decodes through the same `HashedUrl` → `ClientController.connect` path as
      the deep link. Entry point: a "Direct Connect" button added to `IntroMenu`,
      shown only on `Application.ApplicationType.Android`.
- [ ] Expose `--offline` / `--unsafe` equivalents as environment flags in
      `AndroidEnvironment`/`AndroidSettings`
      — deferred to Phase 5: `--offline` is a *server* flag (irrelevant to the
      client); the only client-side flag is `--unsafe` (`BrainOutClient.unsafe`,
      accept unsigned packages), which is meaningful only once data packages
      land, so it is decided together with signing in Phase 5.

### Phase 5 — Data packages & signing
> Decision (confirmed): **unsafe for debug now, signing later for release.**
- [x] Unsafe mode: `AndroidLauncher` sets `BrainOutClient.unsafe = true`
      unconditionally (and `offline = true`). This port only talks to self-hosted
      offline servers — the Anthill backend is never used, so data-signature
      verification is moot. Applies to release builds too (they are not
      debuggable). (`make_data` emits unsigned zips when no private key is set.)
- [x] `./gradlew make_data`; client packages copied into
      `android/assets/packages/` (gitignored — build artifacts).
      `AndroidEnvironment.init()` now copies **all** bundled `*.zip` from
      `assets/packages/` into `getFilesDir()/packages/` on first run (not just
      `mainmenu`), because `searchPackages()` scans `Gdx.files.local("packages")`
      and `ZipContentPackage` needs real files. `build.gradle` adds
      `androidResources.noCompress 'zip'`.
- [x] APK size: **~208 MB** debug APK with all 15 client packages bundled
      (~203 MB of data; `mainmenu` alone is 58 MB). Acceptable for sideloaded
      testing; if it must shrink later, drop maps from assets and lean on the
      existing `PackageManager` download-on-first-run path.

### Phase 6 — Touch controls & UI scale
- [x] **`AndroidGameController` rewritten as a twin-stick multi-touch HUD.**
      The old controller's `Stage` never received touch events (only the
      controller itself was in the `InputMultiplexer`, and `touchDown/Dragged/Up`
      were not forwarded to it) — so the `Touchpad` knob never moved and the
      player could not move. The rewrite tracks raw multi-touch pointers itself:
      - **Floating** left stick (left half) = move; right stick (right half) =
        360° aim (`absoluteAim`) with **auto-fire** past a threshold
        (`beginLaunch`/`endLaunch`); pushing the left stick to the edge = run
        (`beginRun`/`endRun`). Sticks appear wherever the thumb lands.
      - A `Stage` holds only the action buttons (those still consume Stage input,
        forwarded on a button hit-test): **reload / weapon-switch / use / crouch**.
      - HUD is active only in `action` / `actionWithNoMouseLocking`; held inputs
        are released on mode change. UI is built lazily on first activation (the
        `touchpad-*` skin drawables only load with the mainmenu package).
      - Dead, unreachable `move`-mode (OK/Cancel) UI removed; tuning constants in
        `AndroidConstants.Touch`.
- [x] Skins `touchpad-background` / `touchpad-move` / `touchpad-aim` present.
- [x] Verified component-wise on the emulator (single pointer): left swipe walks
      the player across the map; right swipe raises and tracks the floating aim
      stick; the four action buttons render; entering combat mode no longer
      crashes; sticks hide and don't stick on release. **Two-thumb feel (simultaneous
      move + aim + fire) needs a final pass on a real touchscreen** (the emulator
      mouse is single-pointer).
- [ ] Tune UI scale for phone DPI — menus/dialogs render correctly but are
      desktop-sized (small, centered) on a phone; cosmetic, not blocking.
- [ ] Tune button layout / sensitivity after the on-device pass.

### Phase 7 — Networking
- [ ] Verify KryoNet TCP/UDP reaches the external machine's real IP
      (needs a device — Phase 9)
- [x] Cleartext allowed: `android:usesCleartextTraffic="true"` on `<application>`
      so the HTTP port (36557) works on Android 9+ for the self-hosted /
      direct-connect case (server IP is user-supplied, so a blanket allow rather
      than a domain-scoped `network-security-config`).

### Phase 8 — Server on external machine
- [x] Documented (see **Server Quick Reference** below): `./gradlew server:dist`,
      run jar with `--offline`, bind `0.0.0.0`, open ports `36555-36557`
- [x] Documented connection string format `host;tcp;udp;http` → base64 →
      `brainout://...`
- [x] Noted limitation: online events / Battle Pass unavailable in `--offline`
      (no EventService); dailies + core gameplay work

### Phase 9 — Build, sign, test loop
- [x] Debug build installed via `adb install` on an emulator
      (Pixel 8, API 34, x86_64) and launched.
- [x] **Full scenario verified end-to-end (2026-06):** start offline server
      (`--mode free --settings server-free.json --map freeplay-maps.shuffle
      --offline`) → launch client → accept privacy → offline init → enter server
      IP (`10.0.2.2` = host loopback from the emulator) → **KryoNet connect**
      (server logs "New client connected / Player 1") → package + map download
      (`CSConnected → CSPackagesLoad → CSMapDownload → CSMapLoad → CSGame`) →
      **spawned into the freeplay map and rendered touch controls** (joystick +
      fire button). No fatal errors.
- [x] Release keystore + signed release APK. Signing reads
      `android/keystore.properties` (gitignored: `storeFile/storePassword/
      keyAlias/keyPassword`) → `signingConfigs.release`; generate the keystore
      with `keytool -genkeypair -keystore android/brainout-release.jks -alias
      brainout -keyalg RSA -keysize 2048 -validity 10000`. Build with
      `gradlew.bat :android:assembleRelease -PwithAndroid` →
      `android/build/outputs/apk/release/android-release.apk` (~212 MB, verified
      signed via `apksigner verify`). **R8/minify left OFF** (string-named
      content classes would be stripped), so no proguard keep-rules are needed;
      `minifyEnabled false` for release.
- [x] Install on a real device: `adb install android-release.apk` (arm64-v8a /
      armeabi-v7a natives bundled). Pending: the maintainer's on-device pass
      (movement/aim/fire by hand, button feel, UI scale).

### Server deployment
- [x] `bin/server/` ships a Docker + plain-Java deploy: `Dockerfile`,
      `docker-compose.yml`, `run-offline.sh`, and `DEPLOY.md` (prereqs: `server:dist`
      + `make_data`; ports 36555/tcp, 36556/udp, 36557/tcp; freeplay/lobby/duel
      modes; connection-string / `brainout://` deep-link recipe).

## Risks

- **Reflection / R8.** The project instantiates content classes by string name
  (`annotation-detector`, custom `Reflection`). Aggressive ProGuard/R8 keep
  rules are required; ART reflection differences are the most likely source of
  runtime crashes.
- **Asset size & data signing** is the second-largest risk.
- **gdx 1.11 API drift** is mechanical but spans several files.

## Server Quick Reference (external machine, offline)

```
# build the server
./gradlew server:dist

# run offline, bound to all interfaces (ports tcp;udp;http = 36555;36556;36557)
java -jar bin/server/brainout-server.jar --offline

# connection string the Android client must decode:
#   <host>;36555;36556;36557   ->  base64  ->  brainout://<base64>
# example for localhost:
#   localhost;36555;36556;36557 -> brainout://bG9jYWxob3N0OzM2NTU1OzM2NTU2OzM2NTU3
```
