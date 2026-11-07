"""Single-password auth via a signed, expiring session cookie."""
from __future__ import annotations

from fastapi import Request
from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer

from .config import settings

COOKIE_NAME = "panel_session"
_serializer = URLSafeTimedSerializer(settings.secret_key, salt="panel-session")


def make_cookie() -> str:
    return _serializer.dumps({"admin": True})


def is_authenticated(request: Request) -> bool:
    token = request.cookies.get(COOKIE_NAME)
    if not token:
        return False
    try:
        _serializer.loads(token, max_age=settings.session_max_age)
        return True
    except (BadSignature, SignatureExpired):
        return False


def check_password(password: str) -> bool:
    # constant-time-ish compare
    expected = settings.admin_password
    if len(password) != len(expected):
        return False
    result = 0
    for a, b in zip(password, expected):
        result |= ord(a) ^ ord(b)
    return result == 0
