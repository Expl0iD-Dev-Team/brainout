# Brain/Out offline server image.
#
# Build context is bin/server/, which must already contain the built artifacts:
#   * brainout-server.jar   (./gradlew server:dist)
#   * packages/*.zip         (./gradlew make_data)
#   * the *.json / *.shuffle config files (tracked in the repo)
#
# Build:  docker build -t brainout-server:offline .
# Run:    docker run --rm -p 36555:36555/tcp -p 36556:36556/udp -p 36557:36557/tcp brainout-server:offline
FROM eclipse-temurin:17-jre

WORKDIR /app

# Server jar, configuration, content packages and the map building blocks.
COPY brainout-server.jar ./
COPY *.json ./
COPY *.shuffle ./
COPY packages ./packages
# Freeplay/lobby/duel modes load .map files from here (e.g. maps/fr-*.map);
# without them the server returns HTTP 500 on /map ("cannot serialize map").
COPY maps ./maps

# tcp / udp / http  (KryoNet TCP, KryoNet UDP, HTTP)
EXPOSE 36555/tcp 36556/udp 36557/tcp

ENTRYPOINT ["java", "-XX:+UseG1GC", "-Xms64m", "-Xmx512m", "-Djava.net.preferIPv4Stack=true", "-jar", "brainout-server.jar"]

# Default: offline freeplay. Override at `docker run` to change mode/map.
CMD ["--mode", "free", "--settings", "server-free.json", "--map", "freeplay-maps.shuffle", "--offline"]
