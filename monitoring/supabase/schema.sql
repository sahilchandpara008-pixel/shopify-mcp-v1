-- ============================================================================
-- DISCLOSED Employee-Monitoring System — Supabase schema
-- ============================================================================
-- Paste the CONTENTS of this file into the Supabase SQL editor and click "Run".
-- (Do NOT paste the filename.) The script is idempotent: safe to re-run.
--
-- Security model:
--   * anon role          -> may INSERT into all tables + upload to bucket
--                           (this is what the Windows agent uses)
--   * authenticated role -> may SELECT everything + read bucket objects
--                           (this is what the dashboard admin uses)
--
-- IMPORTANT: the agent and dashboard must use the LEGACY anon JWT key
-- (it starts with "eyJ..."), NOT a newer "sb_publishable_..." key.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
create table if not exists public.devices (
    device_id    text primary key,
    employee_name text,
    hostname     text,
    os           text,
    app_version  text,
    first_seen   timestamptz not null default now(),
    last_seen    timestamptz not null default now()
);

create table if not exists public.activity_samples (
    id          bigint generated always as identity primary key,
    device_id   text not null references public.devices(device_id) on delete cascade,
    ts          timestamptz not null default now(),
    active_app  text,
    window_title text,
    is_idle     boolean not null default false,
    idle_seconds integer not null default 0
);

create table if not exists public.screenshots (
    id           bigint generated always as identity primary key,
    device_id    text not null references public.devices(device_id) on delete cascade,
    ts           timestamptz not null default now(),
    storage_path text not null,
    width        integer,
    height       integer
);

create table if not exists public.events (
    id         bigint generated always as identity primary key,
    device_id  text not null references public.devices(device_id) on delete cascade,
    ts         timestamptz not null default now(),
    type       text not null,
    detail     text
);

-- Helpful indexes for dashboard queries (idempotent).
create index if not exists activity_samples_device_ts_idx on public.activity_samples (device_id, ts desc);
create index if not exists screenshots_device_ts_idx       on public.screenshots      (device_id, ts desc);
create index if not exists events_device_ts_idx            on public.events           (device_id, ts desc);
create index if not exists events_type_idx                 on public.events           (type);

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------
alter table public.devices          enable row level security;
alter table public.activity_samples enable row level security;
alter table public.screenshots      enable row level security;
alter table public.events           enable row level security;

-- Postgres has no "CREATE POLICY IF NOT EXISTS", so drop-then-create for idempotency.

-- devices ---------------------------------------------------------------------
drop policy if exists devices_anon_insert       on public.devices;
drop policy if exists devices_anon_update        on public.devices;
drop policy if exists devices_auth_select        on public.devices;

create policy devices_anon_insert on public.devices
    for insert to anon with check (true);
-- The agent upserts its own row each boot (last_seen); allow anon update too.
create policy devices_anon_update on public.devices
    for update to anon using (true) with check (true);
create policy devices_auth_select on public.devices
    for select to authenticated using (true);

-- activity_samples ------------------------------------------------------------
drop policy if exists activity_anon_insert on public.activity_samples;
drop policy if exists activity_auth_select on public.activity_samples;

create policy activity_anon_insert on public.activity_samples
    for insert to anon with check (true);
create policy activity_auth_select on public.activity_samples
    for select to authenticated using (true);

-- screenshots -----------------------------------------------------------------
drop policy if exists screenshots_anon_insert on public.screenshots;
drop policy if exists screenshots_auth_select on public.screenshots;

create policy screenshots_anon_insert on public.screenshots
    for insert to anon with check (true);
create policy screenshots_auth_select on public.screenshots
    for select to authenticated using (true);

-- events ----------------------------------------------------------------------
drop policy if exists events_anon_insert on public.events;
drop policy if exists events_auth_select on public.events;

create policy events_anon_insert on public.events
    for insert to anon with check (true);
create policy events_auth_select on public.events
    for select to authenticated using (true);

-- ---------------------------------------------------------------------------
-- Private storage bucket "screenshots"
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public)
values ('screenshots', 'screenshots', false)
on conflict (id) do update set public = false;

-- Storage object policies.
drop policy if exists screenshots_bucket_anon_insert on storage.objects;
drop policy if exists screenshots_bucket_auth_select on storage.objects;

-- anon may upload (insert) objects into the screenshots bucket only.
create policy screenshots_bucket_anon_insert on storage.objects
    for insert to anon
    with check (bucket_id = 'screenshots');

-- authenticated may read objects from the screenshots bucket only.
create policy screenshots_bucket_auth_select on storage.objects
    for select to authenticated
    using (bucket_id = 'screenshots');

-- ============================================================================
-- Done. Next steps:
--   1. Authentication > Users > "Add user" with "Auto Confirm User" ticked
--      to create your dashboard admin login.
--   2. Copy the Project URL and the LEGACY anon key (eyJ...) from
--      Project Settings > API into the agent config.json and the dashboard.
-- ============================================================================
