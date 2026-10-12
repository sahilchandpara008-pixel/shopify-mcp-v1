@echo off
REM Build the agent as a windowless (--noconsole) Windows executable.
REM Run from this folder in a normal command prompt (venv recommended).

python -m pip install -r requirements.txt || goto :err

pyinstaller --noconfirm --onefile --noconsole ^
  --name monitor_agent ^
  agent.py || goto :err

echo.
echo Built dist\monitor_agent.exe
echo Copy config.example.json next to it as config.json and fill in your values.
goto :eof

:err
echo BUILD FAILED
exit /b 1
