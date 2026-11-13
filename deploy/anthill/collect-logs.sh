#!/usr/bin/env bash
# Collects what is needed to look into a problem players report (a match that
# never loads, a disconnect, a crash) into one archive that can be sent on.
# Nothing secret goes in: access tokens are cut out of the service logs.
#
#   ./deploy/anthill/collect-logs.sh [hours, default 3]
#
# Writes brainout-logs-<time>.tar.gz into the current directory.
set -uo pipefail
export LC_ALL=C   # free/df/uptime print their headers in the system language otherwise
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

HOURS="${1:-3}"
NAME="brainout-logs-$(date +%Y%m%d-%H%M%S)"
OUT="$(mktemp -d)/$NAME"
mkdir -p "$OUT/game-servers"

# tokens in request URLs (access_token=eyJ...) and JWTs anywhere else
scrub() { sed -E 's/(access_token=)[^ &"]+/\1<cut>/g; s/eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_.-]+/<token>/g'; }

{
    echo "time:    $(date -Is)"
    echo "host:    $PUBLIC_HOST"
    echo "uptime:  $(uptime -p 2>/dev/null)"
    echo "memory:  $(free -m 2>/dev/null | awk '/Mem:/ {print $3 " MB used of " $2}')"
    echo "disk:    $(df -h "$REPO" 2>/dev/null | awk 'NR==2 {print $4 " free"}')"
    echo "bundle:  $SERVER_BUNDLE"
    echo "repo:    $(git -C "$REPO" log --oneline -1 2>/dev/null)"
    echo "load:    $(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null) on $(nproc 2>/dev/null) CPUs"
    echo "game master up since: $(docker inspect -f '{{.State.StartedAt}}' anthill_game_master 2>/dev/null)"
    echo
    echo "top CPU users:"
    ps -eo pcpu,rss,comm --sort=-pcpu 2>/dev/null | head -8
    echo
    echo "containers restarting or recently started:"
    docker ps --format '{{.Names}}	{{.Status}}' 2>/dev/null | grep -E 'Restarting|seconds|About a minute' || echo "  none"
} > "$OUT/summary.txt"

# docker logs of a container start when it was (re)created: right after
# deploy.sh they hold only the last minutes, and the problem is not in them
started="$(docker inspect -f '{{.State.StartedAt}}' anthill_game_master 2>/dev/null)"
if [ -n "$started" ] && [ $(( $(date +%s) - $(date -d "$started" +%s 2>/dev/null || echo 0) )) -lt 600 ]; then
    echo "NOTE: the services were restarted less than 10 minutes ago (deploy.sh?)."
    echo "      Their logs start there. Reproduce the problem first, then run this again."
fi

"$HERE/check.sh" > "$OUT/check.txt" 2>&1

docker ps -a --format '{{.Names}}\t{{.Status}}' > "$OUT/containers.txt" 2>&1

for c in anthill_game_master anthill_game_controller anthill_login anthill_market anthill_profile brainout_nginx; do
    docker logs --since "${HOURS}h" "$c" 2>&1 | scrub | tail -3000 > "$OUT/$c.log"
done

# the rooms the game master knows about, and the hosts it sends them to
myq dev_game > "$OUT/rooms.txt" <<SQL
SELECT r.room_id, s.game_server_name, r.players, r.max_players, r.state, r.location, r.settings
  FROM rooms r LEFT JOIN game_servers s ON s.game_server_id = r.game_server_id;
SELECT host_id, host_address, host_enabled, host_state, host_heartbeat, host_memory, host_cpu FROM hosts;
SQL

# game server logs from the last hours, newest first, at most 20
LOGS="$ANTHILL_DIR/data/game-controller/logs"
if [ -d "$LOGS" ]; then
    find "$LOGS" -type f -mmin "-$((HOURS * 60))" -printf '%T@ %p\n' 2>/dev/null \
        | sort -rn | head -20 | cut -d' ' -f2- \
        | while read -r f; do scrub < "$f" > "$OUT/game-servers/$(basename "$f")"; done
fi

tar -czf "$NAME.tar.gz" -C "$(dirname "$OUT")" "$NAME"
rm -rf "$(dirname "$OUT")"
echo "written: $(pwd)/$NAME.tar.gz ($(du -h "$NAME.tar.gz" | cut -f1))"
