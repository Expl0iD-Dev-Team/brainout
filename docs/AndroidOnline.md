# Android online build — plan (Track B / AndroidParity)

The Android port currently runs **offline only**: `AndroidLauncher` forces
`app.offline = true` because the online path crashes on Android. This documents
why and the plan to ship an **online** Android build.

## Root cause

The online stack is: `anthill-runtime-java 0.2.10` → **Unirest 1.4.9**
(`com.mashape.unirest`) → **Apache HttpClient** (`org.apache.http`, +httpcore,
+httpmime). Android ships a *stripped* legacy `org.apache.http` on the
**bootclasspath**, which always shadows any bundled copy. The stripped version is
missing API the full client uses — e.g. `AllowAllHostnameVerifier.INSTANCE` —
so the first online call throws `NoSuchFieldError` (seen via
`Online.release()` → `Unirest.shutdown()`). See `core/build.gradle` (unirest
swapped to 1.4.9) and the note in `BrainOutClient` dispose.

You cannot fix this by bundling a newer httpclient: the bootclasspath copy wins.
The package must be **relocated** so Android's `org.apache.http` is never hit.

## Approach — relocate the HTTP stack for the Android build

1. **Shaded artifact.** Add a small Gradle subproject (e.g. `:anthill-android`)
   that uses the **Shadow** plugin to bundle `anthill-runtime-java` + `unirest`
   + `httpclient`/`httpcore`/`httpmime` and **relocate** `org.apache.http` →
   `shaded.org.apache.http` (and `org.apache.commons.codec`/`logging` if pulled).
   Because Unirest's references are rewritten in the same pass, the relocated
   classes resolve to the bundled full client, never the Android boot one.
2. **Wire the android module** to depend on the shaded artifact and **exclude**
   the original `anthill-runtime-java` / `unirest` / `org.apache.httpcomponents`
   from the `:android` configuration so only the relocated copy is present.
3. **Flip offline off** on Android: make `AndroidLauncher` not force
   `app.offline = true` (gate behind a build flag so an offline APK is still
   possible).
4. **`ENV_SERVICE` via `BuildConfig`.** Add `buildConfigField` for the env URL
   and have `AndroidEnvironment` pass it through (the desktop already reads
   `brainout.env_service`; Android wires it via BuildConfig). Point it at the
   self-hosted backend.

## Risks

- Unirest 1.4.9 may reference `org.apache.http` reflectively or via service
  files (`META-INF/services`) — the relocation must also rewrite those, or the
  shaded client won't load. Verify the merged service descriptors.
- TLS: Android online needs HTTPS to the backend (the local stack is plain
  HTTP). This dovetails with the "deploy on Ubuntu + TLS" track — an online
  Android APK realistically needs the TLS staging backend first.
- Other online-only natives/paths not exercised offline may surface once
  `offline=false` (e.g. the same JZMQ/native concerns are server-side only, so
  not expected here, but the online init flow is newly exercised on device).

## Verification

- Build the online APK, run on the emulator pointed at the backend, watch
  logcat for `onlineInited → Services discovered → Authenticating → Auth done`
  (the same chain the desktop client now completes), then reaching the menu.
- Then matchmake into a match (depends on the in-game join working).

## Build it

```bash
# offline APK (default, unchanged behaviour)
./gradlew :android:assembleDebug -PwithAndroid

# online APK against the local stack (10.0.2.2 = host loopback from the emulator)
./gradlew :android:assembleDebug -PwithAndroid -PbrainoutOnline=true \
    -PbrainoutEnvService=http://10.0.2.2:9503
```

`usesCleartextTraffic="true"` is already set in the manifest, so the online APK
can talk plain HTTP to the local backend — TLS is a production concern, not a
blocker for local verification.

## Status

- [x] Root-caused (Unirest → Apache HttpClient vs Android stripped boot copy).
- [x] `:anthill-android` shaded artifact — relocates `org.apache.http` →
      `shaded.org.apache.http` (also commons-codec/logging), bundles
      anthill-runtime + unirest + websocket/slf4j, excludes `org.json` (core
      declares it directly, so bundling would duplicate).
- [x] `:android` consumes it via the `shaded` configuration and excludes the
      un-relocated `anthill-runtime-java` / `unirest-java` / `httpcomponents`.
      `checkDuplicateClasses` passes.
- [x] `AndroidLauncher`: `app.offline = !BuildConfig.ONLINE_ENABLED`; the env URL
      is pushed into the `brainout.env_service` system property from a **static
      block** (Version resolves it in a static initializer).
- [x] Verified in the built APK: **4 dex files reference
      `shaded/org/apache/http`, zero reference `Lorg/apache/http/`** — the
      stripped Android copy can never be reached.
- [x] **Ran the online APK on the emulator — the shading works on device.**
      logcat shows the full chain:
      `CSOnlineInit → onlineInited → Services discovered → Authenticating →
      Auth done. → CSWaitForUser`, and every HTTP stack frame is
      `shaded.org.apache.http.*` (no `NoSuchFieldError`). The client then
      matchmakes into the lobby and reaches
      `CSConnecting → [kryonet] Connecting: 127.0.0.1:38000/38001`.
- [ ] Actual in-game connection from the emulator — blocked by transport, not by
      the port (see below).
- [ ] TLS staging backend for a real (non-emulator) online build.

## Reaching the backend from the emulator

The emulator resolves `localhost` to *itself*, while the backend advertises
`localhost` addresses (env discovery location, discovery's `external` entries,
and the controller's `gs_host`). Two ways around it:

- **`adb reverse`** (what we used): map the host's ports into the emulator, so
  `localhost:<port>` works unchanged and the desktop client keeps working too.
  Set up the anthill service ports plus the game pool — and narrow the pool
  first (`ports_pool_from/to`), since reverses are per-port:
  ```bash
  for p in 9501 9502 9503 9506 9508 9510 9511 9512 9514 9516 9517 9518; do adb reverse tcp:$p tcp:$p; done
  for p in $(seq 38000 38010); do adb reverse tcp:$p tcp:$p; done
  ```
  **Limitation: `adb reverse` is TCP-only.** kryonet needs TCP *and* UDP, so the
  in-game connection fails in `UdpConnection.send` even though login and
  matchmaking succeed. Good enough to validate the shading + online init, not
  enough to actually play.
- **Host LAN IP** (works for TCP+UDP, and for real devices): point the env
  discovery location, discovery `external` addresses and `gs_host` at the host's
  LAN address. We tried `192.168.0.215` — Docker binds `0.0.0.0` but the host
  firewall refused the connections, so it needs a firewall rule. This is the
  path for real-device testing, and it disappears entirely once the stack runs
  on the Ubuntu server.

### Note on jitpack

The Shadow plugin wouldn't resolve until jitpack was content-filtered in the
buildscript: jitpack claims **every** `com.github.*` group and "resolves" it,
which stops Gradle before it reaches the plugin portal. `excludeGroup
"com.github.johnrengelman"` fixes it.
