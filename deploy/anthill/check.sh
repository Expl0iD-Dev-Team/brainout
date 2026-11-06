#!/usr/bin/env bash
# Health check of the Brain/Out backend on this host. Prints one line per check
# and exits non-zero if any of them fails. deploy.sh runs it at the end.
#
#   ./deploy/anthill/check.sh
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

failed=0
ok()   { printf '  OK    %s\n' "$*"; }
bad()  { printf '  FAIL  %s\n' "$*"; failed=1; }
warn() { printf '  WARN  %s\n' "$*"; }

[ -n "$PUBLIC_HOST" ] || { echo "PUBLIC_HOST is not set (config.env)"; exit 1; }

echo "containers:"
expected="mysql dev-redis-1 rabbitmq brainout_nginx anthill_login anthill_discovery anthill_environment anthill_profile
anthill_game_master anthill_game_controller anthill_store anthill_market anthill_message anthill_social anthill_event
anthill_leaderboard anthill_promo anthill_static anthill_report anthill_blog"
now="$(date +%s)"
for c in $expected; do
    state="$(docker inspect -f '{{.State.Status}} {{.RestartCount}} {{.State.StartedAt}}' "$c" 2>/dev/null)"
    read -r status restarts started_at <<< "$state"
    case "$status" in
        running)
            started="$(date -d "$started_at" +%s 2>/dev/null || echo 0)"
            # A crash-looping container shows as "running" between restarts.
            # RestartCount only counts Docker's automatic restarts, so a fresh
            # start that followed one of those means it is still crashing.
            if [ "${restarts:-0}" -gt 0 ] && [ $((now - started)) -lt 60 ]; then
                warn "$c restarted by Docker $restarts times, last start ${started_at%.*} (crash loop?)"
            else
                ok "$c"
            fi ;;
        "") bad "$c does not exist" ;;
        *)  bad "$c is $status" ;;
    esac
done

echo "HTTPS (certificate checked against state/tls/ca.crt):"
env_reply="$(https_get 9503 "/$GAME/$VERSION" 2>&1)"
if echo "$env_reply" | grep -q '"discovery"'; then ok "environment: $env_reply"; else bad "environment: $env_reply"; fi

disc_reply="$(https_get 9502 "/services/login,game,market,profile" 2>&1)"
if echo "$disc_reply" | grep -q '"market"'; then ok "discovery lists login, game, market, profile"; else bad "discovery: $disc_reply"; fi

for svc in login:9501 profile:9512 game:9508 store:9516 market:9519 message:9511; do
    name="${svc%%:*}"; port="${svc##*:}"
    code="$(curl -s -o /dev/null -m 10 -w '%{http_code}' --noproxy '*' --cacert "$TLS_DIR/ca.crt" \
        --connect-to "$PUBLIC_HOST:$((port + TLS_PORT_OFFSET)):127.0.0.1:$((port + TLS_PORT_OFFSET))" \
        "https://$PUBLIC_HOST:$((port + TLS_PORT_OFFSET))/")"
    # any HTTP answer means nginx, TLS and the service are wired up; 000 means no answer
    if [ "$code" != "000" ] && [ "$code" != "502" ] && [ "$code" != "504" ]; then ok "$name answers (HTTP $code)"; else bad "$name: HTTP $code"; fi
done

echo "certificate:"
if openssl x509 -checkend $((30 * 86400)) -noout -in "$TLS_DIR/server.crt" >/dev/null; then
    ok "server certificate valid until $(openssl x509 -enddate -noout -in "$TLS_DIR/server.crt" | cut -d= -f2)"
else
    bad "server certificate expires within 30 days: re-run deploy.sh"
fi

echo "game controller:"
# not grep -q: it exits on the first match, docker logs then dies of SIGPIPE and
# pipefail turns the whole pipeline into a failure once the log is long
if docker logs anthill_game_controller --since 24h 2>&1 | grep 'Connected to Game Master' >/dev/null; then
    ok "connected to the game master"
else
    bad "no 'Connected to Game Master' in the controller log for 24h"
fi

echo "accounts:"
SALT="$(sed -n 's/^ *passwords_salt: *//p' "$ANTHILL_DIR/docker-compose.yml" | head -1 | tr -d '"')"
default_hash="$(CRED="dev:root" SALT="$SALT" PW="anthill" "$PYTHON" -c \
    'import base64,hashlib,hmac,os;print(base64.b64encode(hmac.new((os.environ["CRED"]+os.environ["SALT"]).encode(),os.environ["PW"].encode(),hashlib.sha256).digest()).decode())')"
current_hash="$(docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" -N -e \
    "SELECT password FROM credential_passwords WHERE credential='dev:root'" dev_login 2>/dev/null)"
if [ -z "$current_hash" ]; then
    bad "dev:root account not found"
elif [ "$current_hash" = "$default_hash" ]; then
    bad "dev:root still has the default password: re-run deploy.sh"
else
    ok "dev:root password is not the default"
fi
scopes="$(docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PW" -N -e \
    "SELECT gamespace_scopes FROM gamespace WHERE gamespace_id=$GAMESPACE_ID" dev_login 2>/dev/null)"
if echo ",$scopes," | grep -qE ',(game_root|game_mod|game_editor|game_ban|static_upload),'; then
    bad "player scopes include admin scopes: $scopes"
else
    ok "player scopes have no admin scopes"
fi

echo "listening on all interfaces (should be only nginx, ssh and game rooms):"
if command -v ss >/dev/null; then
    ss -Hltnu 2>/dev/null | awk '{print $1, $5}' \
        | grep -vE ' (127\.[0-9.]+|\[::1\]):[0-9]+$' | sort -u | sed 's/^/        /'
fi

echo
if [ "$failed" = 0 ]; then echo "all checks passed"; else echo "some checks FAILED"; fi
exit "$failed"
