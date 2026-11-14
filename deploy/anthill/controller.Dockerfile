# Anthill game-controller + a Java 17 JRE so it can spawn the (Java) Brain/Out
# dedicated server as a child process.
#
# The stock anthillplatform/anthill-game-controller image is Debian buster
# (EOL, max openjdk-11 via apt), but the server is built for Java 17. So instead
# of apt we copy a pinned Temurin 17 JRE — the same runtime bin/server uses on
# Linux, so the libGDX desktop natives the headless server loads are already
# known-good there.
#
# Build:  docker build -f controller.Dockerfile -t brainout/anthill-game-controller:jre17 .
# Used by docker-compose.override.yml in the anthill-dev/dev stack.

FROM eclipse-temurin:17-jre AS jre

FROM anthillplatform/anthill-game-controller:latest

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

# The controller mounts /tmp as a noexec tmpfs, but the online server loads a
# native JZMQ .so from java.io.tmpdir. Provide an exec-able tmp dir on the image
# layer; the deployment's run.sh points -Djava.io.tmpdir here.
RUN mkdir -p /opt/brainout-tmp && chmod 1777 /opt/brainout-tmp

# sanity: fail the build if java isn't runnable
RUN java -version

# Reach login and the game master on localhost instead of their public HTTPS
# addresses (see controller_local_discovery.py). The build fails if the patch
# no longer applies to the image's anthill-common.
COPY controller_local_discovery.py /tmp/brainout_local_discovery.py
RUN SP="$(python3 -c 'import site; print(site.getsitepackages()[0])')" \
 && mv /tmp/brainout_local_discovery.py "$SP/brainout_local_discovery.py" \
 && echo "import brainout_local_discovery" > "$SP/brainout_local_discovery.pth" \
 && python3 -c "from anthill.common import discover as d; assert d.Discovery.get_service_external.__name__ == 'get_service_local'"
