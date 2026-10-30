@echo off
setlocal
REM Lance le pont ScribUp sous Windows (cree l'environnement Python au premier lancement).
cd /d "%~dp0"

if exist .venv\Scripts\python.exe goto run

REM Python 3.12 de preference : python-rtmidi (liaison MIDI avec FL Studio)
REM n'existe pas encore en version prete a l'emploi pour Python 3.13 et plus sous Windows.
set "PY="
py -3.12 -c "import sys" >nul 2>&1 && set "PY=py -3.12"
if not defined PY py -3 -c "import sys" >nul 2>&1 && set "PY=py -3"
if not defined PY python -c "import sys" >nul 2>&1 && set "PY=python"
if not defined PY goto nopython

echo Installation de l'environnement Python (une seule fois)...
%PY% -m venv .venv || goto fail
.venv\Scripts\python -m pip install --upgrade pip || goto fail
.venv\Scripts\python -m pip install -r requirements.txt || goto fail

:run
.venv\Scripts\python scribup_bridge.py %*
pause
exit /b

:nopython
echo.
echo Python n'est pas installe sur ce PC.
echo.
echo  1. Va sur https://www.python.org/downloads/windows/
echo  2. Prends "Python 3.12.x" puis "Windows installer (64-bit)"
echo  3. Au debut de l'installation, coche "Add python.exe to PATH"
echo  4. Relance start_bridge.bat
echo.
pause
exit /b 1

:fail
echo.
echo L'installation a echoue (voir les messages ci-dessus).
echo Verifie que Python 3.12 est installe (les versions 3.13 et plus ne marchent pas encore).
if exist .venv rmdir /s /q .venv
pause
exit /b 1
