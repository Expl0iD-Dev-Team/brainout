#!/usr/bin/env bash
# Restores the Anthill databases from a backup.sh dump. Everything written since
# that backup (new accounts, progress) is lost.
#
#   ./deploy/anthill/restore.sh /var/backups/brainout/db-<time>.sql.gz
#
# The secrets, CA and Anthill keys (state-<time>.tar.gz) are not touched; unpack
# that archive by hand if this host lost them (docs/ServerSetup.md).
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

DUMP="${1:?usage: restore.sh <db-<time>.sql.gz>}"
[ -f "$DUMP" ] || die "not found: $DUMP"
gzip -t "$DUMP" || die "$DUMP is not a valid gzip file"

echo "This replaces every Anthill database with the contents of:"
echo "  $DUMP"
read -r -p "Type 'restore' to continue: " answer
[ "$answer" = "restore" ] || die "cancelled"

echo "stopping the services (MySQL keeps running)"
running="$(cd "$ANTHILL_DIR" && docker compose ps --services --status running | grep -vE '^(mysql|redis|rabbitmq)$' || true)"
[ -n "$running" ] && dc stop $running

echo "loading the dump"
zcat "$DUMP" | docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" 2>&1 \
    | grep -v "Using a password on the command line" || true

echo "starting the services"
dc up -d --no-build
echo "done; run ./deploy/anthill/check.sh in a minute"
