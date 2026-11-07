@echo off
REM One-time setup: create venv and install dependencies.
cd /d "%~dp0"
python -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements.txt
echo.
echo Done. Configure panel\.env (copy from .env.example), then run.bat
