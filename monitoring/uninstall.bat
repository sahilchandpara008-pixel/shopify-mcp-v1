@echo off
REM ===========================================================================
REM  DISCLOSED monitoring — uninstaller.  RUN AS ADMINISTRATOR.
REM  Stops and removes both scheduled tasks, kills the processes, and removes
REM  the firewall rules. Leaves %INSTALL_DIR% (logs/buffer) for review; delete
REM  it manually if desired.
REM ===========================================================================

setlocal
set INSTALL_DIR=C:\ProgramData\Monitor
set AGENT_TASK=WorkplaceMonitorAgent
set WATCHDOG_TASK=WorkplaceMonitorWatchdog

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo This script must be run as Administrator.
    exit /b 1
)

echo === Stopping and removing scheduled tasks ===
schtasks /End    /TN "%WATCHDOG_TASK%" >nul 2>&1
schtasks /End    /TN "%AGENT_TASK%"    >nul 2>&1
schtasks /Delete /TN "%WATCHDOG_TASK%" /F >nul 2>&1
schtasks /Delete /TN "%AGENT_TASK%"    /F >nul 2>&1

echo === Killing processes ===
taskkill /IM monitor_watchdog.exe /F >nul 2>&1
taskkill /IM monitor_agent.exe    /F >nul 2>&1

echo === Removing firewall rules ===
netsh advfirewall firewall delete rule name="Workplace Monitor Agent (out)" >nul 2>&1
netsh advfirewall firewall delete rule name="Workplace Monitor Watchdog (out)" >nul 2>&1

echo.
echo Uninstall complete. Logs/buffer remain in %INSTALL_DIR% (delete manually if desired).
endlocal
