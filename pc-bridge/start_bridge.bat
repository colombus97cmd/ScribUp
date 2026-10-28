@echo off
REM Lance le pont ScribUp sous Windows (crée l'environnement Python au premier lancement).
cd /d "%~dp0"
if not exist .venv (
    echo Installation de l'environnement Python...
    py -3 -m venv .venv || python -m venv .venv
    .venv\Scripts\python -m pip install --upgrade pip
    .venv\Scripts\python -m pip install -r requirements.txt
)
.venv\Scripts\python scribup_bridge.py %*
pause
