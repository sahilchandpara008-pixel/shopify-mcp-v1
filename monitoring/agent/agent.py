"""
DISCLOSED employee-monitoring agent (Windows, user session).

This agent is intentionally VISIBLE and NON-COVERT:
  * It shows a one-time on-screen consent/disclosure notice on first launch.
  * It keeps a system-tray icon the whole time it runs, with a
    "What is recorded?" menu item that re-shows the notice.
  * It NEVER logs keystrokes, and NEVER touches the microphone or camera.
  * It does NOT hide its process, and does NOT disable Task Manager.

What it records (all disclosed to the employee):
  * Periodic screenshots of the screen (downscaled, low quality JPEG).
  * The foreground application name + window title, every ~30s.
  * Idle/active state derived from Windows GetLastInputInfo (time since the
    last input event — NOT the content of any input).

Everything is buffered in a local SQLite file so nothing is lost offline, and
uploaded to Supabase when connectivity returns.

All settings are read from config.json next to this program.
"""

from __future__ import annotations

import base64
import ctypes
import ctypes.wintypes
import io
import json
import os
import socket
import sqlite3
import sys
import threading
import time
import traceback
import uuid
from datetime import datetime, timezone

import requests

APP_VERSION = "1.0.0"

# --------------------------------------------------------------------------- #
# Paths — everything lives next to the executable/script in a WRITABLE folder.
# --------------------------------------------------------------------------- #
def app_dir() -> str:
    if getattr(sys, "frozen", False):          # PyInstaller one-file/one-dir
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


BASE_DIR = app_dir()
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
DB_PATH = os.path.join(BASE_DIR, "buffer.sqlite")
HEARTBEAT_PATH = os.path.join(BASE_DIR, "agent.heartbeat")
DEVICE_ID_PATH = os.path.join(BASE_DIR, "device_id.txt")
LOG_PATH = os.path.join(BASE_DIR, "agent.log")
CONSENT_FLAG_PATH = os.path.join(BASE_DIR, "consent_shown.flag")


def log(msg: str) -> None:
    line = f"{datetime.now(timezone.utc).isoformat()} {msg}"
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except OSError:
        pass
    print(line, flush=True)


# --------------------------------------------------------------------------- #
# Config — re-read from disk each loop so intervals/thresholds are adjustable
# with no rebuild.
# --------------------------------------------------------------------------- #
DEFAULT_CONFIG = {
    "company_name": "Example Corp",
    "employee_name": "",
    "supabase_url": "",
    "supabase_anon_key": "",
    "screenshot_interval_sec": 10,
    "activity_interval_sec": 30,
    "idle_threshold_sec": 60,
    "max_screenshot_width": 1600,
    "jpeg_quality": 40,
    "upload_batch": 20,
    "telegram_bot_token": "",
    "telegram_chat_id": "",
    "notify_on_tamper": True,
}


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
            cfg.update(json.load(fh))
    except FileNotFoundError:
        log(f"WARNING: {CONFIG_PATH} not found; using defaults.")
    except (OSError, json.JSONDecodeError) as exc:
        log(f"WARNING: could not read config.json ({exc}); using last defaults.")
    return cfg


# --------------------------------------------------------------------------- #
# Stable device id
# --------------------------------------------------------------------------- #
def get_device_id() -> str:
    try:
        with open(DEVICE_ID_PATH, "r", encoding="utf-8") as fh:
            val = fh.read().strip()
            if val:
                return val
    except OSError:
        pass
    val = str(uuid.uuid4())
    try:
        with open(DEVICE_ID_PATH, "w", encoding="utf-8") as fh:
            fh.write(val)
    except OSError:
        pass
    return val


DEVICE_ID = get_device_id()
HOSTNAME = socket.gethostname()


# --------------------------------------------------------------------------- #
# Local SQLite buffer (offline-safe queue)
# --------------------------------------------------------------------------- #
class Buffer:
    """Thread-safe local queue of pending rows + screenshots."""

    def __init__(self, path: str):
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.execute("pragma journal_mode=WAL")
        self._conn.executescript(
            """
            create table if not exists q_activity(
                id integer primary key autoincrement,
                ts text, active_app text, window_title text,
                is_idle integer, idle_seconds integer);
            create table if not exists q_events(
                id integer primary key autoincrement,
                ts text, type text, detail text);
            create table if not exists q_screens(
                id integer primary key autoincrement,
                ts text, width integer, height integer,
                jpeg_b64 text);
            """
        )
        self._conn.commit()

    def add_activity(self, ts, app, title, is_idle, idle_seconds):
        with self._lock:
            self._conn.execute(
                "insert into q_activity(ts,active_app,window_title,is_idle,idle_seconds)"
                " values(?,?,?,?,?)",
                (ts, app, title, 1 if is_idle else 0, idle_seconds),
            )
            self._conn.commit()

    def add_event(self, ts, type_, detail):
        with self._lock:
            self._conn.execute(
                "insert into q_events(ts,type,detail) values(?,?,?)",
                (ts, type_, detail),
            )
            self._conn.commit()

    def add_screen(self, ts, width, height, jpeg_bytes):
        with self._lock:
            self._conn.execute(
                "insert into q_screens(ts,width,height,jpeg_b64) values(?,?,?,?)",
                (ts, width, height, base64.b64encode(jpeg_bytes).decode("ascii")),
            )
            self._conn.commit()

    def fetch(self, table, limit):
        with self._lock:
            cur = self._conn.execute(
                f"select * from {table} order by id asc limit ?", (limit,)
            )
            cols = [c[0] for c in cur.description]
            return [dict(zip(cols, row)) for row in cur.fetchall()]

    def delete(self, table, ids):
        if not ids:
            return
        with self._lock:
            self._conn.executemany(
                f"delete from {table} where id=?", [(i,) for i in ids]
            )
            self._conn.commit()

    def pending_counts(self):
        with self._lock:
            return {
                t: self._conn.execute(f"select count(*) from {t}").fetchone()[0]
                for t in ("q_activity", "q_events", "q_screens")
            }


# --------------------------------------------------------------------------- #
# Supabase REST client (uses the legacy anon key)
# --------------------------------------------------------------------------- #
class Supabase:
    def __init__(self, cfg: dict):
        self.url = cfg["supabase_url"].rstrip("/")
        self.key = cfg["supabase_anon_key"]

    @property
    def configured(self) -> bool:
        return bool(self.url and self.key)

    def _headers(self, extra=None):
        h = {
            "apikey": self.key,
            "Authorization": f"Bearer {self.key}",
            "Content-Type": "application/json",
        }
        if extra:
            h.update(extra)
        return h

    def upsert_device(self, employee_name):
        now = datetime.now(timezone.utc).isoformat()
        payload = {
            "device_id": DEVICE_ID,
            "employee_name": employee_name,
            "hostname": HOSTNAME,
            "os": f"Windows ({sys.platform})",
            "app_version": APP_VERSION,
            "last_seen": now,
        }
        r = requests.post(
            f"{self.url}/rest/v1/devices",
            headers=self._headers({"Prefer": "resolution=merge-duplicates"}),
            data=json.dumps(payload),
            timeout=15,
        )
        r.raise_for_status()

    def insert_rows(self, table, rows):
        if not rows:
            return
        r = requests.post(
            f"{self.url}/rest/v1/{table}",
            headers=self._headers({"Prefer": "return=minimal"}),
            data=json.dumps(rows),
            timeout=30,
        )
        r.raise_for_status()

    def upload_screenshot(self, storage_path, jpeg_bytes):
        r = requests.post(
            f"{self.url}/storage/v1/object/screenshots/{storage_path}",
            headers={
                "apikey": self.key,
                "Authorization": f"Bearer {self.key}",
                "Content-Type": "image/jpeg",
                "x-upsert": "true",
            },
            data=jpeg_bytes,
            timeout=60,
        )
        r.raise_for_status()


# --------------------------------------------------------------------------- #
# Windows APIs — foreground window + idle time (NO keystroke content)
# --------------------------------------------------------------------------- #
def get_foreground() -> tuple[str, str]:
    """Return (process_exe_name, window_title). Best-effort; never raises."""
    try:
        user32 = ctypes.windll.user32
        kernel32 = ctypes.windll.kernel32
        psapi = ctypes.windll.psapi

        hwnd = user32.GetForegroundWindow()
        length = user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value or ""

        pid = ctypes.wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))

        PROCESS_QUERY_LIMITED = 0x1000
        h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED, False, pid.value)
        app = ""
        if h:
            try:
                name_buf = ctypes.create_unicode_buffer(260)
                psapi.GetModuleBaseNameW(h, None, name_buf, 260)
                app = name_buf.value or ""
            finally:
                kernel32.CloseHandle(h)
        return app, title
    except Exception:
        return "", ""


class LASTINPUTINFO(ctypes.Structure):
    _fields_ = [("cbSize", ctypes.wintypes.UINT),
                ("dwTime", ctypes.wintypes.DWORD)]


def get_idle_seconds() -> int:
    """Seconds since the last user input event (time only; no input content)."""
    try:
        info = LASTINPUTINFO()
        info.cbSize = ctypes.sizeof(info)
        if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
            return 0
        tick = ctypes.windll.kernel32.GetTickCount()
        return max(0, (tick - info.dwTime) // 1000)
    except Exception:
        return 0


# --------------------------------------------------------------------------- #
# Screenshot capture (downscale wide screens; low-quality JPEG)
# --------------------------------------------------------------------------- #
def capture_screenshot(max_width: int, quality: int):
    """Return (jpeg_bytes, width, height) or None on failure."""
    try:
        import mss
        from PIL import Image

        with mss.mss() as sct:
            shot = sct.grab(sct.monitors[0])  # full virtual screen
            img = Image.frombytes("RGB", shot.size, shot.rgb)
    except Exception as exc:
        log(f"screenshot capture failed: {exc}")
        return None

    if img.width > max_width:
        ratio = max_width / img.width
        img = img.resize((max_width, max(1, int(img.height * ratio))))

    out = io.BytesIO()
    img.save(out, format="JPEG", quality=int(quality), optimize=True)
    return out.getvalue(), img.width, img.height


# --------------------------------------------------------------------------- #
# On-screen disclosure notice (MessageBox) + tray icon
# --------------------------------------------------------------------------- #
def notice_text(cfg: dict) -> str:
    return (
        f"{cfg.get('company_name', 'Your employer')} — Workplace Monitoring Notice\n\n"
        "This computer is a company device and activity on it is monitored, as "
        "described in your employment agreement.\n\n"
        "WHAT IS RECORDED:\n"
        "  • Periodic screenshots of this screen.\n"
        "  • The active application and window title.\n"
        "  • Whether the session is idle or active (time since last input).\n\n"
        "WHAT IS NOT RECORDED:\n"
        "  • Keystrokes are NOT logged.\n"
        "  • The microphone and camera are NOT used.\n"
        "  • Nothing is hidden: this program runs visibly with a tray icon.\n\n"
        "A tray icon is shown the whole time monitoring is active. You can open "
        "its menu at any time and choose “What is recorded?” to see this "
        "notice again."
    )


def show_message_box(cfg: dict) -> None:
    try:
        MB_OK = 0x0
        MB_ICONINFORMATION = 0x40
        MB_TOPMOST = 0x40000
        ctypes.windll.user32.MessageBoxW(
            0, notice_text(cfg),
            f"{cfg.get('company_name', 'Workplace')} Monitoring",
            MB_OK | MB_ICONINFORMATION | MB_TOPMOST,
        )
    except Exception as exc:
        log(f"could not show message box: {exc}")


def maybe_show_consent(cfg: dict) -> None:
    """Show the disclosure MessageBox once per install (first launch)."""
    if os.path.exists(CONSENT_FLAG_PATH):
        return
    show_message_box(cfg)
    try:
        with open(CONSENT_FLAG_PATH, "w", encoding="utf-8") as fh:
            fh.write(datetime.now(timezone.utc).isoformat())
    except OSError:
        pass


def _tray_image():
    """Build a simple, recognizable tray icon (eye-like) at runtime."""
    from PIL import Image, ImageDraw

    img = Image.new("RGB", (64, 64), (24, 110, 200))
    d = ImageDraw.Draw(img)
    d.ellipse((10, 20, 54, 44), fill=(255, 255, 255))
    d.ellipse((26, 24, 42, 40), fill=(24, 110, 200))
    d.ellipse((30, 28, 38, 36), fill=(10, 10, 10))
    return img


def run_tray(cfg_provider) -> None:
    """Run the system-tray icon (blocking). Mandatory visible indicator."""
    try:
        import pystray
    except Exception as exc:
        log(f"pystray unavailable, tray icon disabled: {exc}")
        return

    def on_what(icon, item):
        show_message_box(cfg_provider())

    def on_about(icon, item):
        try:
            ctypes.windll.user32.MessageBoxW(
                0,
                f"Workplace monitoring agent v{APP_VERSION}\n"
                f"Device: {HOSTNAME}\n"
                "Monitoring is active and visible by design.",
                "About monitoring", 0x40,
            )
        except Exception:
            pass

    menu = pystray.Menu(
        pystray.MenuItem("What is recorded?", on_what, default=True),
        pystray.MenuItem("About", on_about),
    )
    icon = pystray.Icon(
        "workplace_monitor",
        _tray_image(),
        f"{cfg_provider().get('company_name','Workplace')} monitoring — active",
        menu,
    )
    icon.run()


# --------------------------------------------------------------------------- #
# Single-instance guard (named mutex)
# --------------------------------------------------------------------------- #
def acquire_single_instance() -> bool:
    try:
        kernel32 = ctypes.windll.kernel32
        kernel32.CreateMutexW(None, False, "Global\\WorkplaceMonitorAgentMutex")
        ERROR_ALREADY_EXISTS = 183
        return kernel32.GetLastError() != ERROR_ALREADY_EXISTS
    except Exception:
        return True  # if we can't check, don't block the only instance


# --------------------------------------------------------------------------- #
# Uploader thread — drains the local buffer to Supabase when online
# --------------------------------------------------------------------------- #
def uploader_loop(buf: Buffer, stop: threading.Event):
    while not stop.is_set():
        cfg = load_config()
        sb = Supabase(cfg)
        if not sb.configured:
            stop.wait(10)
            continue
        try:
            # events
            rows = buf.fetch("q_events", cfg["upload_batch"])
            if rows:
                sb.insert_rows("events", [
                    {"device_id": DEVICE_ID, "ts": r["ts"],
                     "type": r["type"], "detail": r["detail"]} for r in rows])
                buf.delete("q_events", [r["id"] for r in rows])

            # activity
            rows = buf.fetch("q_activity", cfg["upload_batch"])
            if rows:
                sb.insert_rows("activity_samples", [
                    {"device_id": DEVICE_ID, "ts": r["ts"],
                     "active_app": r["active_app"], "window_title": r["window_title"],
                     "is_idle": bool(r["is_idle"]), "idle_seconds": r["idle_seconds"]}
                    for r in rows])
                buf.delete("q_activity", [r["id"] for r in rows])

            # screenshots: upload the JPEG to storage, then insert the row
            rows = buf.fetch("q_screens", max(1, cfg["upload_batch"] // 4))
            done = []
            for r in rows:
                jpeg = base64.b64decode(r["jpeg_b64"])
                day = r["ts"][:10]
                storage_path = f"{DEVICE_ID}/{day}/{r['ts'].replace(':', '-')}.jpg"
                sb.upload_screenshot(storage_path, jpeg)
                sb.insert_rows("screenshots", [{
                    "device_id": DEVICE_ID, "ts": r["ts"],
                    "storage_path": storage_path,
                    "width": r["width"], "height": r["height"]}])
                done.append(r["id"])
            buf.delete("q_screens", done)
        except requests.RequestException as exc:
            log(f"upload deferred (offline?): {exc}")
            stop.wait(10)
        except Exception as exc:
            log(f"uploader error: {exc}\n{traceback.format_exc()}")
            stop.wait(10)
        else:
            stop.wait(2)


# --------------------------------------------------------------------------- #
# Main monitoring loop
# --------------------------------------------------------------------------- #
def monitor_loop(buf: Buffer, stop: threading.Event):
    last_shot = 0.0
    last_activity = 0.0

    # Try to register the device immediately (best-effort; buffer covers offline).
    try:
        cfg = load_config()
        sb = Supabase(cfg)
        if sb.configured:
            sb.upsert_device(cfg["employee_name"])
    except Exception as exc:
        log(f"initial device upsert deferred: {exc}")

    buf.add_event(datetime.now(timezone.utc).isoformat(), "agent_started",
                  f"v{APP_VERSION} host={HOSTNAME}")

    while not stop.is_set():
        cfg = load_config()
        now = time.time()

        # Heartbeat every loop so the watchdog can tell we're alive.
        try:
            with open(HEARTBEAT_PATH, "w", encoding="utf-8") as fh:
                fh.write(datetime.now(timezone.utc).isoformat())
        except OSError:
            pass

        # Activity sample
        if now - last_activity >= cfg["activity_interval_sec"]:
            last_activity = now
            idle = get_idle_seconds()
            is_idle = idle > cfg["idle_threshold_sec"]
            app, title = get_foreground()
            buf.add_activity(datetime.now(timezone.utc).isoformat(),
                             app, title, is_idle, idle)

        # Screenshot
        if now - last_shot >= cfg["screenshot_interval_sec"]:
            last_shot = now
            shot = capture_screenshot(cfg["max_screenshot_width"], cfg["jpeg_quality"])
            if shot:
                jpeg, w, h = shot
                buf.add_screen(datetime.now(timezone.utc).isoformat(), w, h, jpeg)

        stop.wait(1)


# --------------------------------------------------------------------------- #
# Entry point
# --------------------------------------------------------------------------- #
def main():
    log(f"agent starting v{APP_VERSION} device={DEVICE_ID} host={HOSTNAME}")

    if not acquire_single_instance():
        log("another agent instance is already running; exiting.")
        return

    cfg = load_config()
    maybe_show_consent(cfg)

    buf = Buffer(DB_PATH)
    stop = threading.Event()

    threads = [
        threading.Thread(target=monitor_loop, args=(buf, stop), daemon=True),
        threading.Thread(target=uploader_loop, args=(buf, stop), daemon=True),
    ]
    for t in threads:
        t.start()

    # The tray icon runs on the main thread (pystray requirement on Windows).
    try:
        run_tray(load_config)
    except KeyboardInterrupt:
        pass
    finally:
        # If the tray exits, keep monitoring alive — the agent should not stop
        # just because the icon was closed. Block on the worker threads.
        log("tray exited; monitoring continues in background threads.")
        try:
            while any(t.is_alive() for t in threads):
                time.sleep(1)
        except KeyboardInterrupt:
            stop.set()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # never crash silently
        log(f"FATAL: {exc}\n{traceback.format_exc()}")
        raise
