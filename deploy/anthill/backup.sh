#!/usr/bin/env bash
# Backs up the Brain/Out backend: a dump of every Anthill database (accounts,
# profiles, store, ...) plus the passwords, TLS files and Anthill keys.
#
#   ./deploy/anthill/backup.sh
#
# Files go to BACKUP_DIR (config.env, default /var/backups/brainout):
#   db-<time>.sql.gz      restore with restore.sh
#   state-<time>.tar.gz   state/ (secrets, CA) and anthill-dev/dev/keys
# Backups older than KEEP_DAYS (default 14) are removed. Daily via cron, see
# docs/ServerSetup.md.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

KEEP_DAYS="${KEEP_DAYS:-14}"
ts="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
umask 077

dbs="$(docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SHOW DATABASES LIKE 'dev\_%'" 2>/dev/null | tr '\n' ' ')"
[ -n "$dbs" ] || die "no dev_* databases found (is the mysql container running?)"

docker exec "$MYSQL_CONTAINER" mysqldump -uroot -p"$MYSQL_ROOT_PW" \
    --single-transaction --routines --events --default-character-set=utf8mb4 \
    --databases $dbs 2>/dev/null | gzip > "$BACKUP_DIR/db-$ts.sql.gz.part"
# a dump that died half way must not look like a good backup
gzip -t "$BACKUP_DIR/db-$ts.sql.gz.part"
zcat "$BACKUP_DIR/db-$ts.sql.gz.part" | tail -1 | grep -q "Dump completed" || die "mysqldump did not complete"
mv "$BACKUP_DIR/db-$ts.sql.gz.part" "$BACKUP_DIR/db-$ts.sql.gz"

tar -czf "$BACKUP_DIR/state-$ts.tar.gz" \
    --exclude='state/server-bundle' -C "$HERE" state \
    -C "$ANTHILL_DIR" keys

find "$BACKUP_DIR" -maxdepth 1 \( -name 'db-*.sql.gz' -o -name 'state-*.tar.gz' \) -mtime +"$KEEP_DAYS" -delete

echo "backup written:"
ls -lh "$BACKUP_DIR/db-$ts.sql.gz" "$BACKUP_DIR/state-$ts.tar.gz"
