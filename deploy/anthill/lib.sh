# Shared settings and helpers for deploy.sh, check.sh, backup.sh and restore.sh.
# Sourced, not executed.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
STATE_DIR="${STATE_DIR:-$HERE/state}"        # git-ignored: secrets, TLS, rendered config

if [ -f "$HERE/config.env" ]; then
    set -a; . "$HERE/config.env"; set +a
fi
if [ -f "$STATE_DIR/secrets.env" ]; then
    set -a; . "$STATE_DIR/secrets.env"; set +a
fi

PUBLIC_HOST="${PUBLIC_HOST:-}"
TLS_PORT_OFFSET="${TLS_PORT_OFFSET:-10000}"
PORTS_FROM="${PORTS_FROM:-38000}"
PORTS_TO="${PORTS_TO:-38020}"
SERVER_BUNDLE="${SERVER_BUNDLE:-}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/brainout}"

# Upstream sources, pinned so a redeploy can't pick up unreviewed changes.
ANTHILL_REPO="${ANTHILL_REPO:-https://github.com/anthill-platform/anthill-dev.git}"
ANTHILL_REF="${ANTHILL_REF:-af86f38505f399736c639dc5897474f5b75da824}"
ANTHILL_DIR="${ANTHILL_DIR:-$REPO/../anthill-dev/dev}"
MARKET_REPO="${MARKET_REPO:-https://github.com/anthill-platform/anthill-market.git}"
MARKET_REF="${MARKET_REF:-e4cc4b3}"
MARKET_DIR="${MARKET_DIR:-$REPO/../anthill-market}"

CONTROLLER_IMAGE="${CONTROLLER_IMAGE:-brainout/anthill-game-controller:jre17}"
MARKET_IMAGE="${MARKET_IMAGE:-brainout/anthill-market:$MARKET_REF}"
NGINX_IMAGE="${NGINX_IMAGE:-nginx:1.27-alpine}"

# The anthill-dev MySQL is only reachable from this host (render.py binds it to
# 127.0.0.1), so its stock credentials are kept.
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql}"
MYSQL_ROOT_PW="${MYSQL_ROOT_PW:-Root123}"

GAMESPACE_ID="${GAMESPACE_ID:-1}"
GAME="${GAME:-brainout}"
VERSION="${VERSION:-valpha2}"
DEPLOYMENT_ID="${DEPLOYMENT_ID:-1}"

TLS_DIR="$STATE_DIR/tls"

PYTHON=""
for _py in python3 python; do
    if command -v "$_py" >/dev/null 2>&1 && "$_py" -c "import sys" >/dev/null 2>&1; then
        PYTHON="$_py"; break
    fi
done

step() { echo; echo "==== $* ===="; }
die() { echo "ERROR: $*" >&2; exit 1; }

# run SQL from stdin against database $1; real errors stay visible
myq() {
    { echo "SET NAMES utf8mb4;"; cat; } \
        | docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" "$1" 2>&1 \
        | grep -vE "Using a password on the command line|Found option without preceding group" || true
}

wait_for() { # wait_for "<desc>" <cmd...>
    local desc="$1"; shift; local n=100
    until "$@" >/dev/null 2>&1; do
        n=$((n-1)); [ $n -le 0 ] && { echo "timed out waiting for: $desc"; return 1; }
        sleep 3
    done
}

db_exists() {
    docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" -N -e \
        "SELECT 1 FROM information_schema.schemata WHERE schema_name='$1'" 2>/dev/null | grep -q 1
}
table_exists() {
    docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" -N -e \
        "SELECT 1 FROM information_schema.tables WHERE table_schema='$1' AND table_name='$2'" 2>/dev/null | grep -q 1
}

dc() { ( cd "$ANTHILL_DIR" && docker compose "$@" ); }

# https request to one of our services, checked against our CA. Connects to
# 127.0.0.1 but verifies the certificate for PUBLIC_HOST, so it works from the
# server itself even when the router has no NAT loopback.
https_get() { # https_get <service port> <path>
    local port=$(( $1 + TLS_PORT_OFFSET ))
    curl -sS -m 10 --noproxy '*' --cacert "$TLS_DIR/ca.crt" \
        --connect-to "$PUBLIC_HOST:$port:127.0.0.1:$port" \
        "https://$PUBLIC_HOST:$port$2"
}
