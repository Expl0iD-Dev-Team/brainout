"""Panel configuration, read from environment / a .env file."""
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# panel/ directory (this file is panel/app/config.py)
PANEL_DIR = Path(__file__).resolve().parent.parent
REPO_DIR = PANEL_DIR.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="PANEL_", env_file=".env", extra="ignore")

    # --- auth ---
    admin_password: str = "changeme"          # MUST be overridden in production
    secret_key: str = "dev-secret-change-me"  # signs the session cookie
    session_max_age: int = 60 * 60 * 12       # 12h

    # --- where the game server's config/maps live (read-only, for the catalog) ---
    server_dir: Path = REPO_DIR / "bin" / "server"

    # panel-local scratch dir for generated files (e.g. one-map set), bind-mounted
    # into the game container. Must be reachable by the Docker daemon on deploy.
    generated_dir: Path = PANEL_DIR / "generated"

    # --- the managed game container (offline Docker backend) ---
    game_container: str = "brainout-server"
    game_image: str = "brainout-server:offline"
    tcp_port: int = 36555
    udp_port: int = 36556
    http_port: int = 36557
    # extra args always appended to the launch command
    extra_args: list[str] = ["--offline"]

    # base URL of the game server's HTTP port, for the future /status poll (Phase 2)
    status_url: str | None = None  # e.g. "http://brainout-server:36557"

    # which backend to use; "offline_docker" for now (online Anthill later)
    backend: str = "offline_docker"


settings = Settings()
