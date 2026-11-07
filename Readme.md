# Brain / Out

Source code for [Brain / Out](https://brainout.org/), the game.

## Android and a self-hosted server

This fork adds an Android client that plays online against a backend you run
yourself (a home server is enough). The docs are in Russian:

- [docs/Players.md](docs/Players.md) — for players: installing the APK, controls, sending a crash report.
- [docs/ServerSetup.md](docs/ServerSetup.md) — running the backend on Ubuntu behind NAT.
- [docs/Release.md](docs/Release.md) — releasing a version, updates, the test week.
- [docs/ReleasePlan.md](docs/ReleasePlan.md) — what was done, what was checked, known bugs.

GitHub Actions builds the APK and the server bundle on every push
(`.github/workflows/build.yml`); a `v*` tag creates a draft release.

## How To Build From Source

OpenJDK 11 is required.

First, clone this repo.

Use Gradle Wrapper: `./gradlew <command>` on mac/linux, or `gradlew.bat <command>` on windows

To obtain project, use `./gradlew idea` to generate [IntelliJ IDEA](https://www.jetbrains.com/idea/) project. Once project
   is generated, open `brainout.ipr` within IntelliJ IDEA IDE.

Do NOT let the IDE "load gradle project", just stick with ipr.

Make sure you build your data, call `./gradlew make_data`

Then, 

1. `./gradlew desktop:dist` to build desktop client
2. `./gradlew steam:dist` to build steam client
3. `./gradlew server:dist` to build server

For either of those to work, Game Assets has to be built.

Look at [the troubleshooting document](docs/Troubleshooting.md), it includes several common issues and how to fix them.

## Dependencies

Setup SDK for IntelliJ IDEA:

1. Click Project Structure
2. Select SDKs
3. Click "+", Download JDK
4. Select version 11
5. Download it and rename to "1.8"
6. Run `./gradlew idea` again

On first install, or after any assets changed, make sure to call `./gradlew make_data`

## How to build Game Assets

See [this readme](data/README.md) for how to change Game Assets.

To build them, call `./gradlew make_data`

**NOTE**: the client digitally verifies the built data! So by default, the client
won't be able to start up with locally built data, since the private key is not present in this repository.
Pass `--unsafe` command line argument to the client so it would ignore non-signed packages.
`Offline Unsafe` IDEA configurations already do this.

## Offline build

By default, both the client and the server are trying to participate into the
Alpha game network. Since no keys are present in this repository, for locally run builds please pass
`--offline` command line argument both to the server and the client.
`Offline` IDEA configurations already do this.

## Self-hosted server & Android client

An offline, self-hosted setup (no Alpha/Anthill network) plus a working Android
client is documented in detail in [docs/AndroidPort.md](docs/AndroidPort.md).
Quick start:

### Server

Build once, then run with Docker or plain Java. Full instructions:
[bin/server/DEPLOY.md](bin/server/DEPLOY.md).

```bash
./gradlew server:dist      # builds bin/server/brainout-server.jar
./gradlew make_data        # builds the content packages it serves

cd bin/server
# Docker (recommended on a server):
docker compose up --build -d
# or plain (Java 17 on the host):
./run-offline.sh
```

Ports to open / forward: **36555/tcp**, **36556/udp**, **36557/tcp**
(tcp / udp / http). Runs offline freeplay by default; lobby/duel modes and the
`brainout://` connection-string recipe are covered in DEPLOY.md.

### Android client

The Android module is reactivated (AGP 8, libGDX 1.11, twin-stick touch
controls, offline/direct-connect). Build a signed release APK:

```bash
# one-time: generate a signing keystore (see docs/AndroidPort.md), then
gradlew.bat :android:assembleRelease -PwithAndroid
# -> android/build/outputs/apk/release/android-release.apk
adb install android/build/outputs/apk/release/android-release.apk
```

A prebuilt signed APK is also attached to the project's
[GitHub Releases](../../releases). In-game, use **Direct Connect** / the
"Enter server IP" prompt (or a `brainout://` deep link) to reach your server.

## Run configurations

`Server Lobby` – server hosting "Main Menu". Offline version is offline, Alpha version participates
in the Alpha network.

`Server` – server hosting active game phase. Offline version is offline, Alpha version participates
in the Alpha network.

`Server Editor` – server hosting old non-steam editor. Offline version is offline, Alpha version participates
in the Alpha network. Important to note that client looks for Home User folder `brainout-maps` by default to upload 
any map, use `BRAINOUT_MAPS` environment variable to point client to `<this repo>/bin/server/maps`.

`Desktop Local Offline Unsafe` – a client that tries to connect to server on localhost. 
It uses `--connect brainout://bG9jYWxob3N0OzM2NTU1OzM2NTU2OzM2NTU3` argument
which is bas64-encoded `localhost;36555;36556;36557`

You might also see there's `Desktop Local 2 *` configurations, these are copies under different user,
e.g., you can launch those two in parallel and run two instances of the game on same PC.

`Desktop Alpha` – a client that tries to participate Alpha network.

## Testing

The project has a CI system in place: on every new commit, 
it builds a new version of client/server and deploys it automatically.
There are two environments available:

* `main`, which is built on every master commit
* `test`, which is built on every pull request. A new pull request wipes out the previous one,
  as it is intended to verify the correctness of the pull request.
* `dev`, which is build on every `dev_*` branch commit. Contributors with collaboration permissions
  could push there directly and skip PR requirements to check things quickly.

The clients of those builds are deployed on Dropbox, to get invited there,
please join the Dev Support telegram channel below and ask for invite link.

## Community

If you have development question, please join
[Dev Support](https://t.me/+f8ha1XpCJS1mZmUy) group on Telegram.

Otherwise, feel free to join the [Official Discord Community Server](https://discord.gg/eeqyjeG7P5)
