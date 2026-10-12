@echo off
REM Build the watchdog as a normal (console) Windows executable.
REM Run from this folder in a normal command prompt (venv recommended).

python -m pip install -r requirements.txt || goto :err

pyinstaller --noconfirm --onefile ^
  --name monitor_watchdog ^
  watchdog.py || goto :err

echo.
echo Built dist\monitor_watchdog.exe
goto :eof

:err
echo BUILD FAILED
exit /b 1
