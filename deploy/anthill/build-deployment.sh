#!/usr/bin/env bash
# Assemble an Anthill "deployment" of the Brain/Out dedicated server: the jar +
# content packages + maps + mode settings + the launcher (run.sh). The Anthill
# game-controller extracts a deployment to
#   <binaries_path>/runtime/<game>/<version>/<deployment_id>/
# and runs <binary> (run.sh) from there. With the anthill-dev stack the
# binaries_path is host-mounted, so we can stage straight into it (no upload).
#
# Usage:
#   build-deployment.sh <dest_dir>
#   build-deployment.sh ../../path/to/anthill-dev/dev/data/game-controller/binaries/runtime/brainout/valpha2/1
#
# The server build comes from SERVER_DIR (default bin/server, produced by
# ./gradlew server:dist make_data; deploy.sh points it at an unpacked CI bundle).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="${SERVER_DIR:-$REPO/bin/server}"
DEST="${1:?usage: build-deployment.sh <dest_dir>}"

[ -f "$SRC/brainout-server.jar" ] || { echo "missing $SRC/brainout-server.jar (run ./gradlew server:dist)"; exit 1; }
[ -d "$SRC/packages" ]            || { echo "missing $SRC/packages (run ./gradlew make_data)"; exit 1; }

# start clean so files dropped from a newer build don't linger
case "$DEST" in */runtime/*) rm -rf "$DEST" ;; esac
mkdir -p "$DEST"
# server payload (jar, packages, maps, mode/settings json, shuffles, greetings)
cp -r "$SRC/." "$DEST"/
# drop docker/offline-only helpers that don't belong in the deployment
rm -f "$DEST"/Dockerfile "$DEST"/.dockerignore "$DEST"/docker-compose.yml \
      "$DEST"/run-offline.sh "$DEST"/run.bat "$DEST"/DEPLOY.md 2>/dev/null || true
# the Anthill launcher becomes the deployment "binary" (game_settings.binary=run.sh)
cp "$HERE/anthill-run.sh" "$DEST/run.sh"
chmod +x "$DEST/run.sh" 2>/dev/null || true

echo "Staged deployment into: $DEST"
du -sh "$DEST"
