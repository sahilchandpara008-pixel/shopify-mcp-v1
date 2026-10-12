@echo off
REM ===========================================================================
REM  DISCLOSED monitoring — installer.  RUN AS ADMINISTRATOR.
REM ===========================================================================
REM  Installs into a WRITABLE folder (NOT Program Files) because the agent
REM  writes its heartbeat and offline buffer next to itself.
REM
REM  It creates:
REM    * an ON-LOGON scheduled task running the agent in the user's session,
REM    * an ON-START SYSTEM scheduled task running the watchdog,
REM    * Windows Firewall OUTBOUND allow rules for both executables,
REM  and then starts both.
REM
REM  This installer does NOT disable Task Manager and does NOT hide anything.
REM ===========================================================================

setlocal
set INSTALL_DIR=C:\ProgramData\Monitor
set AGENT_EXE=%INSTALL_DIR%\monitor_agent.exe
set WATCHDOG_EXE=%INSTALL_DIR%\monitor_watchdog.exe
set AGENT_TASK=WorkplaceMonitorAgent
set WATCHDOG_TASK=WorkplaceMonitorWatchdog

echo === Checking for administrator rights ===
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo This script must be run as Administrator.  Right-click ^> Run as administrator.
    exit /b 1
)

echo === Creating install folder %INSTALL_DIR% ===
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

echo === Copying files ===
REM Expects monitor_agent.exe, monitor_watchdog.exe and config.json next to
REM this installer (or in agent\dist and watchdog\dist).
if exist "%~dp0monitor_agent.exe"   copy /Y "%~dp0monitor_agent.exe"   "%AGENT_EXE%"
if exist "%~dp0agent\dist\monitor_agent.exe" copy /Y "%~dp0agent\dist\monitor_agent.exe" "%AGENT_EXE%"
if exist "%~dp0monitor_watchdog.exe" copy /Y "%~dp0monitor_watchdog.exe" "%WATCHDOG_EXE%"
if exist "%~dp0watchdog\dist\monitor_watchdog.exe" copy /Y "%~dp0watchdog\dist\monitor_watchdog.exe" "%WATCHDOG_EXE%"
if exist "%~dp0config.json"         copy /Y "%~dp0config.json"         "%INSTALL_DIR%\config.json"
if exist "%~dp0agent\config.json"   copy /Y "%~dp0agent\config.json"   "%INSTALL_DIR%\config.json"

if not exist "%AGENT_EXE%" (
    echo ERROR: %AGENT_EXE% not found. Build the agent first ^(agent\build.bat^).
    exit /b 1
)
if not exist "%WATCHDOG_EXE%" (
    echo ERROR: %WATCHDOG_EXE% not found. Build the watchdog first ^(watchdog\build.bat^).
    exit /b 1
)
if not exist "%INSTALL_DIR%\config.json" (
    echo ERROR: config.json not found. Place a filled-in config.json next to this installer.
    exit /b 1
)

echo === Adding firewall OUTBOUND allow rules ===
netsh advfirewall firewall delete rule name="Workplace Monitor Agent (out)" >nul 2>&1
netsh advfirewall firewall delete rule name="Workplace Monitor Watchdog (out)" >nul 2>&1
netsh advfirewall firewall add rule name="Workplace Monitor Agent (out)" ^
    dir=out action=allow program="%AGENT_EXE%" enable=yes profile=any
netsh advfirewall firewall add rule name="Workplace Monitor Watchdog (out)" ^
    dir=out action=allow program="%WATCHDOG_EXE%" enable=yes profile=any

echo === Creating agent ON-LOGON task (runs in the user's session) ===
schtasks /Delete /TN "%AGENT_TASK%" /F >nul 2>&1
schtasks /Create /TN "%AGENT_TASK%" /TR "\"%AGENT_EXE%\"" ^
    /SC ONLOGON /RL LIMITED /F

echo === Creating watchdog ON-START SYSTEM task ===
schtasks /Delete /TN "%WATCHDOG_TASK%" /F >nul 2>&1
schtasks /Create /TN "%WATCHDOG_TASK%" /TR "\"%WATCHDOG_EXE%\"" ^
    /SC ONSTART /RU "SYSTEM" /RL HIGHEST /F

echo === Starting both now ===
schtasks /Run /TN "%AGENT_TASK%"    >nul 2>&1
schtasks /Run /TN "%WATCHDOG_TASK%" >nul 2>&1

echo.
echo Install complete.
echo   Agent task    : %AGENT_TASK%    (on logon, user session, LIMITED)
echo   Watchdog task : %WATCHDOG_TASK% (on start, SYSTEM)
echo   Install dir   : %INSTALL_DIR%
echo.
echo The employee will see a one-time notice and a persistent tray icon.
endlocal
