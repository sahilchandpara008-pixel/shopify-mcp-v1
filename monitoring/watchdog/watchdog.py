"""
DISCLOSED monitoring watchdog (Windows, runs as SYSTEM via a scheduled task).

Purpose and ethical scope:
  The ONLY thing this watchdog does to make monitoring resilient is RESTART the
  user-session agent if its heartbeat goes stale, and LOG the interruption.
  It does NOT, and must not, disable Task Manager, hide any process, or block
  the employee from using their machine. "Can't be stopped" here means
  "restarts itself and records the gap" — nothing covert.

  Session-0 isolation note: a SYSTEM service/task cannot capture the user's
  screen. This watchdog therefore never captures anything itself; it only
  relaunches the user-session agent (via that agent's own scheduled task).

Behaviour, every ~15 seconds:
  * If a user is logged in (explorer.exe is running) AND the agent heartbeat
    file is stale (older than STALE_SECONDS), then:
       - insert an 'agent_stopped' event into Supabase,
       - send a Telegram alert (if configured),
       - relaunch the agent via its scheduled task.
  * Debounced: alert/log once per stop, re-armed when the agent recovers.
  * Never exits on error — wrapped in a top-level retry loop.
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
import traceback
from datetime import datetime, timezone

import requests

# Must match the agent's install location (a WRITABLE folder).
BASE_DIR = os.environ.get("MONITOR_DIR", r"C:\\ProgramData\\Monitor")
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
HEARTBEAT_PATH = os.path.join(BASE_DIR, "agent.heartbeat")
DEVICE_ID_PATH = os.path.join(BASE_DIR, "device_id.txt")
LOG_PATH = os.path.join(BASE_DIR, "watchdog.log")

AGENT_TASK_NAME = "WorkplaceMonitorAgent"   # created by install.bat
CHECK_INTERVAL = 15                          # seconds
STALE_SECONDS = 60                           # heartbeat older than this => stopped
HOSTNAME = socket.gethostname()


def log(msg: str) -> None:
    line = f"{datetime.now(timezone.utc).isoformat()} {msg}"
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except OSError:
        pass
    print(line, flush=True)


def load_config() -> dict:
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception as exc:
        log(f"could not read config.json: {exc}")
        return {}


def read_device_id() -> str:
    try:
        with open(DEVICE_ID_PATH, "r", encoding="utf-8") as fh:
            return fh.read().strip()
    except OSError:
        return "unknown-device"


def user_logged_in() -> bool:
    """True if a user is logged in (explorer.exe present)."""
    try:
        out = subprocess.run(
            ["tasklist", "/FI", "IMAGENAME eq explorer.exe"],
            capture_output=True, text=True, timeout=15,
        )
        return "explorer.exe" in out.stdout.lower()
    except Exception as exc:
        log(f"tasklist check failed: {exc}")
        return False


def heartbeat_stale() -> bool:
    try:
        age = time.time() - os.path.getmtime(HEARTBEAT_PATH)
        return age > STALE_SECONDS
    except OSError:
        return True  # no heartbeat file at all => stale/stopped


def relaunch_agent() -> None:
    """Relaunch the agent in the logged-in user's session via its task."""
    try:
        subprocess.run(["schtasks", "/Run", "/TN", AGENT_TASK_NAME],
                       capture_output=True, text=True, timeout=30)
        log(f"requested relaunch of scheduled task {AGENT_TASK_NAME}")
    except Exception as exc:
        log(f"relaunch failed: {exc}")


def insert_event(cfg: dict, type_: str, detail: str) -> None:
    url = (cfg.get("supabase_url") or "").rstrip("/")
    key = cfg.get("supabase_anon_key") or ""
    if not (url and key):
        return
    try:
        requests.post(
            f"{url}/rest/v1/events",
            headers={"apikey": key, "Authorization": f"Bearer {key}",
                     "Content-Type": "application/json", "Prefer": "return=minimal"},
            data=json.dumps([{
                "device_id": read_device_id(),
                "ts": datetime.now(timezone.utc).isoformat(),
                "type": type_, "detail": detail}]),
            timeout=20,
        ).raise_for_status()
    except requests.RequestException as exc:
        log(f"could not insert '{type_}' event (offline?): {exc}")


def send_telegram(cfg: dict, text: str) -> None:
    if not cfg.get("notify_on_tamper", True):
        return
    token = cfg.get("telegram_bot_token") or ""
    chat = cfg.get("telegram_chat_id") or ""
    if not (token and chat):
        return
    try:
        requests.post(
            f"https://api.telegram.org/bot{token}/sendMessage",
            data={"chat_id": chat, "text": text},
            timeout=20,
        ).raise_for_status()
    except requests.RequestException as exc:
        log(f"telegram send failed: {exc}")


def watch() -> None:
    agent_down = False  # debounce latch
    log(f"watchdog online on {HOSTNAME}; interval={CHECK_INTERVAL}s stale>{STALE_SECONDS}s")

    while True:
        cfg = load_config()
        logged_in = user_logged_in()

        if logged_in and heartbeat_stale():
            if not agent_down:
                agent_down = True
                when = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
                name = cfg.get("employee_name") or "(unknown)"
                log(f"agent appears STOPPED (stale heartbeat) at {when}")
                insert_event(cfg, "agent_stopped",
                             f"heartbeat stale on {HOSTNAME}; watchdog relaunching")
                send_telegram(
                    cfg,
                    f"⚠️ Monitoring stopped on {HOSTNAME}, "
                    f"employee {name}, {when}. Watchdog is relaunching the agent.",
                )
            # Always attempt relaunch while it's down.
            relaunch_agent()
        else:
            if agent_down and logged_in and not heartbeat_stale():
                agent_down = False
                log("agent heartbeat recovered; re-armed.")
                insert_event(cfg, "agent_recovered",
                             f"heartbeat recovered on {HOSTNAME}")

        time.sleep(CHECK_INTERVAL)


def main() -> None:
    # Top-level retry loop: the watchdog must never exit on an unexpected error.
    while True:
        try:
            watch()
        except Exception as exc:
            log(f"watchdog crashed, restarting in 10s: {exc}\n{traceback.format_exc()}")
            time.sleep(10)


if __name__ == "__main__":
    main()
