#!/usr/bin/env bash
# Brings up (or updates) the self-hosted Brain/Out online backend on this host.
#
#   1. fill in deploy/anthill/config.env (copy config.env.example)
#   2. ./deploy/anthill/deploy.sh
#
# Safe to re-run: use it again to deploy a new server build or after changing
# config.env. Step-by-step guide: docs/ServerSetup.md.
#
# What it sets up on top of the upstream anthill-dev stack:
#   * every container port bound to 127.0.0.1; nginx terminates HTTPS on
#     19501-19519 with a certificate from a private CA (no domain needed)
#   * random passwords for the Anthill root account and the game server account
#     (kept in state/secrets.env), and player accounts without admin scopes
#   * the real market service instead of a stub
#   * the Brain/Out game registration, game servers and starter economy
#
# Needs: docker with compose >= 2.24.4, git, curl, openssl, python3.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

# ----- 0. prechecks ---------------------------------------------------------
step "0. prechecks"
[ -n "$PUBLIC_HOST" ] || die "PUBLIC_HOST is not set: copy config.env.example to config.env and fill it in"
for c in docker git curl openssl; do command -v "$c" >/dev/null || die "$c not found"; done
[ -n "$PYTHON" ] || die "python3 not found"

compose_version="$(docker compose version --short 2>/dev/null | sed 's/^v//')"
[ -n "$compose_version" ] || die "docker compose plugin not found"
if [ "$(printf '%s\n' 2.24.4 "$compose_version" | sort -V | head -1)" != "2.24.4" ]; then
    die "docker compose $compose_version is too old, need 2.24.4+ (install Docker from download.docker.com)"
fi

SERVER_DIR="$REPO/bin/server"
if [ -n "$SERVER_BUNDLE" ]; then
    [ -f "$SERVER_BUNDLE" ] || die "SERVER_BUNDLE not found: $SERVER_BUNDLE"
    rm -rf "$STATE_DIR/server-bundle"
    mkdir -p "$STATE_DIR/server-bundle"
    tar -xzf "$SERVER_BUNDLE" -C "$STATE_DIR/server-bundle"
    SERVER_DIR="$STATE_DIR/server-bundle/server"
fi
[ -f "$SERVER_DIR/brainout-server.jar" ] || die "no brainout-server.jar in $SERVER_DIR (set SERVER_BUNDLE or run ./gradlew server:dist)"
[ -d "$SERVER_DIR/packages" ] || die "no packages/ in $SERVER_DIR (set SERVER_BUNDLE or run ./gradlew make_data)"
echo "host=$PUBLIC_HOST  https=$((9501 + TLS_PORT_OFFSET))-$((9519 + TLS_PORT_OFFSET))  rooms=$PORTS_FROM-$PORTS_TO  compose=$compose_version"
echo "server build: $SERVER_DIR"

# ----- 1. secrets and TLS ---------------------------------------------------
step "1. secrets and TLS"
mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
if [ ! -f "$STATE_DIR/secrets.env" ]; then
    ( umask 077
      printf 'ROOT_PASSWORD=%s\nSRV_PASS=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" \
          > "$STATE_DIR/secrets.env" )
    echo "generated $STATE_DIR/secrets.env"
fi
set -a; . "$STATE_DIR/secrets.env"; set +a
[ -n "${ROOT_PASSWORD:-}" ] && [ -n "${SRV_PASS:-}" ] || die "secrets.env is missing ROOT_PASSWORD or SRV_PASS"

# openssl prints key generation progress (".+++") on stderr
bash "$HERE/tls.sh" "$TLS_DIR" "$PUBLIC_HOST" 2>&1 | grep -vE '^[.+*-]|^$' || true

# ----- 2. upstream sources --------------------------------------------------
step "2. upstream sources (pinned)"
checkout() { # checkout <repo url> <dir> <ref>
    if [ ! -d "$2/.git" ]; then
        git clone -q "$1" "$2"
    fi
    # only go to the network when the pinned commit isn't here yet, so a
    # redeploy works even while GitHub is unreachable
    git -C "$2" cat-file -e "$3^{commit}" 2>/dev/null || git -C "$2" fetch -q origin
    git -C "$2" -c advice.detachedHead=false checkout -q "$3"
    echo "$(basename "$2") at $(git -C "$2" rev-parse --short HEAD)"
}
# discovery-services.json is rewritten below; discard that before switching refs
git -C "$(dirname "$ANTHILL_DIR")" checkout -q -- . 2>/dev/null || true
checkout "$ANTHILL_REPO" "$(dirname "$ANTHILL_DIR")" "$ANTHILL_REF"
checkout "$MARKET_REPO" "$MARKET_DIR" "$MARKET_REF"
# anthill-dev commits its container scripts (keys/setup.sh, collectd/start.sh)
# without the executable bit, and CRLF from a Windows checkout breaks them too;
# without a working keygen no Anthill service starts.
find "$(dirname "$ANTHILL_DIR")" -name '*.sh' -not -path '*/.git/*' \
    -exec sed -i 's/\r$//' {} + -exec chmod +x {} + 2>/dev/null || true

# ----- 3. configuration -----------------------------------------------------
step "3. render configuration"
export ANTHILL_DIR STATE_DIR PUBLIC_HOST TLS_PORT_OFFSET ROOT_PASSWORD \
       CONTROLLER_IMAGE MARKET_IMAGE NGINX_IMAGE PORTS_FROM PORTS_TO
"$PYTHON" "$HERE/render.py"
dc config -q
echo "compose configuration is valid"

# ----- 4. images ------------------------------------------------------------
step "4. images"
docker build -q -f "$HERE/controller.Dockerfile" -t "$CONTROLLER_IMAGE" "$HERE"
docker build -q -t "$MARKET_IMAGE" "$MARKET_DIR"
# the controller and market images were just built here; there is nothing to pull
pull_services="$(dc config --services | grep -vxE 'anthill_game_controller|anthill_market')"
# a dropped connection mid-download is common on home links; layers already
# fetched are kept, so a retry only resumes what is missing
for attempt in 1 2 3; do
    dc pull -q --ignore-buildable $pull_services && break
    [ "$attempt" = 3 ] && die "could not pull the images (see the errors above)"
    echo "pull failed, retrying ($attempt/3)"; sleep 10
done
dc build -q

# ----- 5. start -------------------------------------------------------------
step "5. start the stack"
dc up -d --no-build --remove-orphans
wait_for "keys"  test -f "$ANTHILL_DIR/keys/public/anthill.pub"
wait_for "mysql" bash -c "docker exec $MYSQL_CONTAINER mysqladmin -uroot -p$MYSQL_ROOT_PW ping 2>/dev/null | grep -q alive"
echo "CREATE DATABASE IF NOT EXISTS dev_market;" | myq mysql
wait_for "dev_login schema"       table_exists dev_login credential_passwords
wait_for "dev_login gamespaces"   table_exists dev_login gamespace
wait_for "dev_environment schema" table_exists dev_environment applications
wait_for "dev_environment envs"   table_exists dev_environment environments
wait_for "dev_store schema"       table_exists dev_store stores
wait_for "dev_game schema"        table_exists dev_game game_servers
echo "stack up"

# ----- 6. accounts and registration ----------------------------------------
step "6. accounts, game registration, scopes"
# Login hashes passwords as base64(HMAC-SHA256(key=credential+salt, msg=password)).
SALT="$(sed -n 's/^ *passwords_salt: *//p' "$ANTHILL_DIR/docker-compose.yml" | head -1 | tr -d '"')"
[ -n "$SALT" ] || die "passwords_salt not found in docker-compose.yml"
ROOT_HASH="$(CRED="dev:root" SALT="$SALT" PW="$ROOT_PASSWORD" "$PYTHON" -c \
    'import base64,hashlib,hmac,os;print(base64.b64encode(hmac.new((os.environ["CRED"]+os.environ["SALT"]).encode(),os.environ["PW"].encode(),hashlib.sha256).digest()).decode())')"

# The root account exists with password "anthill" unless the login service was
# first started with root_user_password; set it either way. It's reachable
# through the public login service, so the default must never survive.
myq dev_login <<SQL
UPDATE credential_passwords SET password='$ROOT_HASH', algorithm='HMACSHA256'
 WHERE credential='dev:root';
INSERT INTO gamespace_aliases (gamespace_name, gamespace_id)
  SELECT '$GAME:desktop', $GAMESPACE_ID FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM gamespace_aliases WHERE gamespace_name='$GAME:desktop');
-- Scopes any player account gets. The client asks for more
-- (ClientConstants.Scopes.SCOPES) but only needs SHOULD_HAVE; the admin scopes
-- game_root/game_mod/game_editor/game_ban/static_upload stay out on purpose.
UPDATE gamespace SET gamespace_scopes=
 'profile,profile_write,game,message_listen,store,group,party,party_create,report_upload,blog,market,event,exec_func_call'
 WHERE gamespace_id=$GAMESPACE_ID;
SQL

myq dev_environment <<SQL
INSERT INTO applications (application_name, application_title, min_api)
  SELECT '$GAME','Brain/Out','0.2' FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM applications WHERE application_name='$GAME');
INSERT INTO application_versions (application_id, version_name, version_environment)
  SELECT application_id, '$VERSION', 1 FROM applications WHERE application_name='$GAME'
  AND NOT EXISTS (SELECT 1 FROM application_versions av JOIN applications a ON av.application_id=a.application_id
                  WHERE a.application_name='$GAME' AND av.version_name='$VERSION');
UPDATE environments SET environment_discovery='https://$PUBLIC_HOST:$((9502 + TLS_PORT_OFFSET))'
 WHERE environment_id=1;
SQL

# ----- 7. tables the services fail to create --------------------------------
step "7. missing tables"
# Some services create a table before the tables its foreign keys point at, so
# on a fresh database that table is never made and the service breaks later:
#   store:       orders -> campaigns           (orders can't be placed)
#   game master: rooms  -> hosts, deployments  (the controller is dropped on
#                its first heartbeat, so no game server ever starts)
# Create them from the service's own SQL, in dependency order.
create_missing_tables() { # <db> <image> <python package> <table...>
    local db="$1" image="$2" pkg="$3"; shift 3
    local sqldir="/usr/local/lib/python3.7/site-packages/anthill/$pkg/sql" made=""
    for t in "$@"; do
        table_exists "$db" "$t" && continue
        docker run --rm --entrypoint sh "$image" -c "cat $sqldir/$t.sql" \
            | docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" "$db" 2>&1 \
            | grep -vE "Using a password on the command line|Found option without preceding group" || true
        table_exists "$db" "$t" || die "could not create $db.$t"
        made="$made $t"
    done
    echo "$db:${made:- nothing missing}"
    [ -n "$made" ]
}
if create_missing_tables dev_store anthillplatform/anthill-store:latest store campaigns campaign_items orders; then
    docker restart anthill_store >/dev/null
fi
if create_missing_tables dev_game anthillplatform/anthill-game-master:latest game/master rooms; then
    docker restart anthill_game_master >/dev/null
fi

# ----- 8. game servers, deployment, economy --------------------------------
step "8. game servers and economy"
export MYSQL_CONTAINER MYSQL_ROOT_PW GAMESPACE_ID GAME VERSION DEPLOYMENT_ID SRV_PASS SALT
bash "$HERE/provision.sh"

# The controller registers itself as a host under PUBLIC_HOST. A host left over
# from an earlier address stays ACTIVE in the master's table, and the master
# keeps sending spawns to it (NO_ROUTE, so no game ever starts). Drop such hosts
# together with their rooms.
myq dev_game <<SQL
DELETE p FROM players p JOIN rooms r ON r.room_id = p.room_id JOIN hosts h ON h.host_id = r.host_id
 WHERE h.host_address <> '$PUBLIC_HOST';
DELETE r FROM rooms r JOIN hosts h ON h.host_id = r.host_id WHERE h.host_address <> '$PUBLIC_HOST';
DELETE FROM hosts WHERE host_address <> '$PUBLIC_HOST';
SQL

STAGE="$ANTHILL_DIR/data/game-controller/binaries/runtime/$GAME/$VERSION/$DEPLOYMENT_ID"
# Docker creates the controller's bind-mounted binaries dir as root; hand it to
# this user (through a throwaway container, so no sudo is needed) to stage into it.
BINARIES="$ANTHILL_DIR/data/game-controller/binaries"
mkdir -p "$BINARIES" 2>/dev/null || true
if [ ! -w "$BINARIES" ] || [ -n "$(find "$BINARIES" ! -user "$(id -u)" -print -quit 2>/dev/null)" ]; then
    docker run --rm -v "$BINARIES:/d" alpine:3 chown -R "$(id -u):$(id -g)" /d
fi
SERVER_DIR="$SERVER_DIR" bash "$HERE/build-deployment.sh" "$STAGE"
cp "$STATE_DIR/service-rewrite.json" "$STAGE/service-rewrite.json"

bash "$HERE/seed-economy.sh"

# ----- 9. restart what read the changed config ------------------------------
step "9. restart services"
# Discovery reads discovery-services.json only for services it doesn't know yet
# and keeps the rest in Redis (db 15), so a changed PUBLIC_HOST or port would
# never reach the clients. Clear it so the file is loaded afresh.
dc exec -T redis redis-cli -n 15 FLUSHDB >/dev/null
docker restart anthill_discovery >/dev/null
wait_for "discovery" bash -c "curl -s --noproxy '*' http://127.0.0.1:9502/services/login | grep -q $PUBLIC_HOST"
# every other service caches the addresses it discovered, so restart them all
anthill_services="$(dc config --services | grep -E '^anthill_' \
    | grep -vxE 'anthill_discovery|anthill_game_controller|anthill_common|anthill_keygen')"
dc restart $anthill_services >/dev/null 2>&1
dc up -d --no-build --force-recreate anthill_game_controller brainout_nginx
wait_for "controller connected to the game master" bash -c \
    "docker logs anthill_game_controller --since 3m 2>&1 | grep -q 'Connected to Game Master'"

# ----- 10. verify -----------------------------------------------------------
step "10. verify"
bash "$HERE/check.sh" || true

ENV_URL="https://$PUBLIC_HOST:$((9503 + TLS_PORT_OFFSET))"
cat <<DONE

Backend for $GAME:$VERSION is up on $PUBLIC_HOST.

Router port forwarding (to this machine):
  TCP      $((9501 + TLS_PORT_OFFSET))-$((9519 + TLS_PORT_OFFSET))   HTTPS services
  TCP+UDP  $PORTS_FROM-$PORTS_TO   game rooms

APK settings (GitHub -> Settings -> Secrets and variables -> Actions -> Variables):
  BRAINOUT_ENV_SERVICE = $ENV_URL
  BRAINOUT_SERVER_CA   = contents of $TLS_DIR/ca.crt

Back up $STATE_DIR (passwords, CA key) and set up backup.sh (docs/ServerSetup.md).
DONE
