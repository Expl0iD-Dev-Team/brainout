@echo off
REM Run the control panel (Windows). Set up first:  setup.bat
call "%~dp0.venv\Scripts\activate.bat"
uvicorn app.main:app --host 0.0.0.0 --port 8080 %*
