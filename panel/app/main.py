"""Brain/Out server control panel — FastAPI app (Track A, MVP).

Pick a preset (event) + map and (re)start the offline game server via Docker;
show status and logs. The actual server control goes through a pluggable
ServerBackend so the online (Anthill) backend can replace it later.
"""
from __future__ import annotations

from dataclasses import asdict
from pathlib import Path

from fastapi import Depends, FastAPI, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, PlainTextResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

from . import auth, catalog
from .config import settings

app = FastAPI(title="Brain/Out Server Control Panel")
templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))

_backend = None
_backend_error: str | None = None


def get_backend():
    """Lazily build the configured backend; degrade gracefully if unavailable."""
    global _backend, _backend_error
    if _backend is not None:
        return _backend
    try:
        from .backend.offline_docker import OfflineDockerBackend
        _backend = OfflineDockerBackend()
        _backend_error = None
    except Exception as e:  # docker not reachable, etc.
        _backend_error = f"{type(e).__name__}: {e}"
        _backend = None
    return _backend


def require_api_auth(request: Request) -> None:
    if not auth.is_authenticated(request):
        raise HTTPException(status_code=401, detail="not authenticated")


# ------------------------------------------------------------------ auth pages
@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    return templates.TemplateResponse("login.html", {"request": request, "error": None})


@app.post("/login")
def login(request: Request, password: str = Form(...)):
    if not auth.check_password(password):
        return templates.TemplateResponse(
            "login.html", {"request": request, "error": "Wrong password"}, status_code=401)
    resp = RedirectResponse("/", status_code=303)
    resp.set_cookie(auth.COOKIE_NAME, auth.make_cookie(), httponly=True,
                    samesite="lax", max_age=settings.session_max_age)
    return resp


@app.post("/logout")
def logout():
    resp = RedirectResponse("/login", status_code=303)
    resp.delete_cookie(auth.COOKIE_NAME)
    return resp


# ----------------------------------------------------------------------- panel
@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    if not auth.is_authenticated(request):
        return RedirectResponse("/login", status_code=303)
    return templates.TemplateResponse("index.html", {"request": request})


# ------------------------------------------------------------------------- api
@app.get("/api/catalog", dependencies=[Depends(require_api_auth)])
def api_catalog():
    out = []
    for p in catalog.list_presets():
        maps = catalog.list_maps(p)
        out.append({
            "id": p.id, "name": p.name, "mode": p.mode,
            "source_kind": p.source_kind,
            "maps": [{"id": m.id, "name": m.name} for m in maps],
        })
    return {"presets": out}


@app.get("/api/status", dependencies=[Depends(require_api_auth)])
def api_status():
    backend = get_backend()
    if backend is None:
        return {"available": False, "error": _backend_error}
    st = backend.status()
    return {"available": True, **asdict(st)}


class ApplyBody(BaseModel):
    preset_id: str
    map_id: str | None = None


@app.post("/api/apply", dependencies=[Depends(require_api_auth)])
def api_apply(body: ApplyBody):
    backend = get_backend()
    if backend is None:
        raise HTTPException(503, _backend_error or "backend unavailable")
    backend.apply(body.preset_id, body.map_id)
    return {"ok": True}


@app.post("/api/start", dependencies=[Depends(require_api_auth)])
def api_start():
    backend = get_backend()
    if backend is None:
        raise HTTPException(503, _backend_error or "backend unavailable")
    backend.start()
    return {"ok": True}


@app.post("/api/stop", dependencies=[Depends(require_api_auth)])
def api_stop():
    backend = get_backend()
    if backend is None:
        raise HTTPException(503, _backend_error or "backend unavailable")
    backend.stop()
    return {"ok": True}


@app.get("/api/logs", response_class=PlainTextResponse, dependencies=[Depends(require_api_auth)])
def api_logs(tail: int = 200):
    backend = get_backend()
    if backend is None:
        return _backend_error or "backend unavailable"
    return backend.logs(tail=tail)
