#!/bin/sh
# Brain/Out dedicated-server launcher used as the Anthill deployment "binary".
#
# The Anthill game-controller spawns this as:  run.sh <sock> <ports> <args...>
# and we forward everything to the server jar. java.io.tmpdir is forced to an
# exec-able dir because the controller mounts /tmp as a noexec tmpfs and the
# online controller (anthill-runtime) loads a native JZMQ .so from tmp.
HERE="$(cd "$(dirname "$0")" && pwd)"

# The game master passes the services' public HTTPS addresses in
# discovery_services. The controller runs on this host, so send the server to
# the services directly instead of out through the router and nginx
# (service-rewrite.json is written by deploy.sh).
if [ -f "$HERE/service-rewrite.json" ] && [ -n "${discovery_services:-}" ]; then
    rewritten="$(python3 -c '
import json, os, sys
mapping = json.load(open(sys.argv[1]))
services = json.loads(os.environ["discovery_services"])
print(json.dumps({k: mapping.get(v.rstrip("/"), v) for k, v in services.items()}))
' "$HERE/service-rewrite.json")" && discovery_services="$rewritten" && export discovery_services
fi

exec java -XX:-OmitStackTraceInFastThrow -server \
    -XX:MaxHeapFreeRatio=15 -XX:MinHeapFreeRatio=15 -XX:+UseG1GC \
    -Xms16m -Xmx256m -XX:InitiatingHeapOccupancyPercent=20 \
    -Djava.net.preferIPv4Stack=true -Djava.io.tmpdir=/opt/brainout-tmp \
    -jar brainout-server.jar "$@"
