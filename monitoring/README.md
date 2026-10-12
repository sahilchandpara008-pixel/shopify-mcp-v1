# Disclosed Employee-Monitoring System

A **transparent, consent-based** activity-monitoring system for a company's own
Windows PCs. It is designed to be **visible, not covert**, and to be used only
where monitoring is disclosed to employees (e.g. in the employment contract).

## Legal & ethical use — read first

This tool is intended **only** for monitoring devices your organization owns,
where the people using them have been clearly told that monitoring is active
and have consented (typically via their employment agreement). Monitoring
without disclosure and a lawful basis may be illegal in your jurisdiction
(GDPR, state/national wiretap and privacy laws, works-council rules, etc.).
**Get written legal/HR sign-off and provide notice before deploying.**

By design, this system:

- ✅ Shows a **one-time on-screen notice** on first launch.
- ✅ Keeps a **persistent system-tray icon** with a *“What is recorded?”* menu.
- ✅ Records only: periodic **screenshots**, **foreground app + window title**,
  and **idle/active** state (time since last input).
- 🚫 Does **not** log keystrokes.
- 🚫 Does **not** use the microphone or camera.
- 🚫 Does **not** hide its process and does **not** disable Task Manager.
- 🔁 “Resilience” is only a **watchdog that restarts the agent and logs the
  gap** — never concealment.

If you need something covert, this is not that tool, and such a change should
not be made to it.

---

## Architecture

```
Windows PC (employee session)                 Supabase (cloud)            Admin browser
┌────────────────────────────┐                ┌──────────────┐           ┌──────────────┐
│ monitor_agent.exe          │  REST + Storage │ Postgres + RLS│  SELECT   │ dashboard/   │
│  • tray icon + notice      │ ───────────────▶│ Storage bucket│◀──────────│ index.html   │
│  • screenshots / activity  │   (anon key)    │  "screenshots"│ (auth key)│ (Netlify)    │
│  • SQLite offline buffer   │                 └──────────────┘           └──────────────┘
│  • heartbeat file          │
└──────────────┬─────────────┘
               │ heartbeat (local file)
┌──────────────▼─────────────┐
│ monitor_watchdog.exe (SYSTEM)                │ restarts agent + logs 'agent_stopped'
└────────────────────────────┘
```

| Part | Folder | What it is |
|------|--------|------------|
| A — Backend | `supabase/schema.sql` | Tables, RLS, private storage bucket |
| B — Agent | `agent/` | Python agent (user session) |
| C — Watchdog | `watchdog/` | Python watchdog (SYSTEM task) |
| Installers | `install.bat`, `uninstall.bat` | Firewall rules + scheduled tasks |
| D — Dashboard | `dashboard/` | Single static HTML page |

---

## Setup

### 1. Supabase backend

1. Create a Supabase project.
2. Open **SQL editor**, paste the **contents** of `supabase/schema.sql`
   (not the filename) and click **Run**. It is idempotent — safe to re-run.
3. Create your admin login under **Authentication → Users → Add user**, and
   **tick “Auto Confirm User”** (otherwise sign-in fails).
4. From **Project Settings → API**, copy:
   - the **Project URL**, and
   - the **legacy anon key** — it starts with `eyJ…`.
     ⚠️ Do **not** use a newer `sb_publishable_…` key; the agent and dashboard
     both need the legacy `eyJ…` key.

### 2. Build the executables (on a Windows machine with Python 3.10+)

```bat
cd agent     && build.bat     & cd ..
cd watchdog  && build.bat     & cd ..
```

This produces `agent\dist\monitor_agent.exe` (windowless) and
`watchdog\dist\monitor_watchdog.exe` (console). The agent is built with
`--noconsole`; the watchdog is a normal console exe.

### 3. Configure

Copy `agent/config.example.json` to `config.json` and fill in:

| Key | Meaning |
|-----|---------|
| `company_name` | Shown in the notice and tray tooltip |
| `employee_name` | Who this device belongs to |
| `supabase_url` | `https://YOUR-ref.supabase.co` |
| `supabase_anon_key` | The **legacy** `eyJ…` anon key |
| `screenshot_interval_sec` | Default **10** (re-read each loop; no rebuild) |
| `activity_interval_sec` | Default 30 |
| `idle_threshold_sec` | Idle if no input for this many seconds (default 60) |
| `max_screenshot_width` | Wide screens are downscaled to this (default 1600) |
| `jpeg_quality` | ~40 keeps uploads small |
| `telegram_bot_token` / `telegram_chat_id` | Optional tamper alerts |
| `notify_on_tamper` | Send Telegram alert when the agent is stopped |

### 4. Install (as Administrator)

Put `monitor_agent.exe`, `monitor_watchdog.exe`, and the filled-in
`config.json` next to `install.bat` (or leave them in `agent\dist` /
`watchdog\dist`), then right-click **install.bat → Run as administrator**.

It installs into **`C:\ProgramData\Monitor\`** — a **writable** folder (the
agent writes its heartbeat and offline buffer next to itself, so it must
**not** go in `Program Files`). It then:

- adds **outbound firewall allow** rules for both exes,
- creates an **on-logon** task for the agent (runs in the user session),
- creates an **on-start SYSTEM** task for the watchdog,
- starts both.

To remove everything: right-click **uninstall.bat → Run as administrator**.

### 5. Deploy the dashboard

Host `dashboard/` on **real static hosting** (Netlify/Vercel/your server):

```bash
netlify deploy --dir=monitoring/dashboard --prod
```

⚠️ Do **not** open it inside a sandboxed preview iframe that blocks outbound
`fetch` to `*.supabase.co` — that produces *“Failed to fetch”* on login. Use a
proper URL.

Open the page, enter your Supabase URL + legacy anon key (stored in
localStorage), then sign in with the admin account.

---

## Dashboard features

- **Overview:** one card per employee — name, online/away/offline, today's
  active vs idle time, last seen. A card shows a red **“stopped today”** chip
  and a global red banner appears if any agent was stopped.
- **Detail (click a card):** active/idle tiles, **top apps by active time**,
  a **screenshot gallery** (signed URLs) with a **date picker**, and an
  **interruptions** panel listing `agent_stopped` events.
- Password **show/hide eye** toggle, **light/dark** theme-aware, no external
  network except the supabase-js CDN and your Supabase project.

---

## How "can't be stopped" works (transparently)

If the employee ends `monitor_agent.exe` in Task Manager, its heartbeat file
goes stale. Within ~15s the SYSTEM watchdog notices (while a user is logged
in), writes an `agent_stopped` event, optionally sends a Telegram alert, and
relaunches the agent via its scheduled task. The restart and the gap are both
visible in the dashboard. Because the watchdog runs as SYSTEM, a standard
employee account cannot end it in Task Manager — but nothing is hidden and
Task Manager itself keeps working normally.

> Session-0 isolation: the SYSTEM watchdog **cannot** capture the screen; only
> the user-session agent does. The watchdog only restarts it.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| "Failed to fetch" on login | Serve the dashboard from real hosting, not a sandbox iframe. |
| Sign-in rejected | Create the user with **Auto Confirm User** ticked. |
| Agent writes nothing / permission errors | Install in `C:\ProgramData\Monitor\`, not `Program Files`. |
| 401 from Supabase | You used `sb_publishable_…`; switch to the legacy `eyJ…` anon key. |
| No screenshots but activity works | Only the user-session agent captures; check the agent task is running on logon. |
