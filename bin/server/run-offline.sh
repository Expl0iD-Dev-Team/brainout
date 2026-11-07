#!/usr/bin/env bash
# Run the Brain/Out server in offline freeplay mode (no Anthill backend).
#
# Requirements:
#   * Java 17 on PATH
#   * brainout-server.jar in this directory  (./gradlew server:dist)
#   * packages/*.zip in this directory        (./gradlew make_data)
#
# Listens on tcp;udp;http = 36555;36556;36557. Open those ports in the firewall.
# Extra args are forwarded (e.g. pass a different --map).
set -e
cd "$(dirname "$0")"

exec java -XX:+UseG1GC -Xms64m -Xmx512m -Djava.net.preferIPv4Stack=true \
    -jar brainout-server.jar \
    --mode free --settings server-free.json --map freeplay-maps.shuffle --offline "$@"
