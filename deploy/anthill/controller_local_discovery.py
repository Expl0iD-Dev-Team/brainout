"""Make the game controller reach Anthill services on localhost.

The controller looks up the login and game master services with
Discovery.get_service_external(), i.e. the addresses meant for players. Those
are https://<public IP>:195xx behind nginx: unreachable from the server itself
without NAT loopback, and signed by a CA the controller doesn't trust.

The controller runs in the host network, and every service is published on
127.0.0.1 with its own port, so take the internal address
(http://anthill_login:9501) and point it at 127.0.0.1 instead.

Installed into the controller image's site-packages with a .pth file, so it is
applied when the interpreter starts (controller.Dockerfile).
"""
import re

try:
    from anthill.common import discover as _discover
except Exception:  # not an Anthill interpreter: nothing to patch
    _discover = None

if _discover is not None:
    _get_service = _discover.Discovery.get_service

    async def get_service_local(self, service_id):
        location = await _get_service(self, service_id, network="internal")
        return re.sub(r"^(https?://)[^:/]+", r"\g<1>127.0.0.1", location)

    _discover.Discovery.get_service_external = get_service_local
