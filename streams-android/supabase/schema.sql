-- =====================================================================
-- STREAMS — complete database setup
-- Paste this whole file into Supabase → SQL Editor → New query → Run.
-- Safe to run once on a fresh project.
--
-- BEFORE RUNNING: replace YOUR_OWNER_EMAIL@example.com (near the bottom)
-- with the email address you will use to log in to the admin area.
-- =====================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------
-- 1. ADMIN ALLOW-LIST
-- ---------------------------------------------------------------------
create table public.admin_emails (
  email      text primary key check (email = lower(email)),
  role       text not null check (role in ('owner', 'manager')),
  created_at timestamptz not null default now()
);

-- Role of the signed-in user ('owner' / 'manager' / null). Used everywhere.
create or replace function public.my_admin_role()
returns text language sql stable security definer set search_path = public as $$
  select role from public.admin_emails
  where email = lower(coalesce(auth.jwt() ->> 'email', ''))
$$;

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select public.my_admin_role() is not null
$$;

create or replace function public.is_owner()
returns boolean language sql stable security definer set search_path = public as $$
  select public.my_admin_role() = 'owner'
$$;

alter table public.admin_emails enable row level security;
create policy "admins read team"   on public.admin_emails for select using (public.is_admin());
create policy "owner adds manager" on public.admin_emails for insert with check (public.is_owner() and role = 'manager');
create policy "owner removes manager" on public.admin_emails for delete using (public.is_owner() and role = 'manager');

-- ---------------------------------------------------------------------
-- 2. SETTINGS (UPI ID etc. — never hardcoded in the app)
-- ---------------------------------------------------------------------
create table public.app_settings (
  id         int primary key default 1 check (id = 1),
  upi_id     text not null,
  payee_name text not null,
  updated_at timestamptz not null default now()
);
insert into public.app_settings (upi_id, payee_name) values ('2728412a@bandhan', 'Streams');

alter table public.app_settings enable row level security;
create policy "anyone reads settings" on public.app_settings for select using (true);
create policy "owner edits settings"  on public.app_settings for update using (public.is_owner()) with check (public.is_owner());

-- ---------------------------------------------------------------------
-- 3. PLANS
-- ---------------------------------------------------------------------
create table public.subscription_plans (
  id            uuid primary key default gen_random_uuid(),
  name          text not null,
  duration_label text not null,
  duration_days int  not null check (duration_days > 0),
  price         numeric(10,2) not null check (price > 0),
  currency      text not null default 'INR',
  active        boolean not null default true,
  sort_order    int not null default 0
);
insert into public.subscription_plans (name, duration_label, duration_days, price, sort_order) values
  ('Trial',         '3 Days',   3,   99, 1),
  ('Silver Plan',   '7 Days',   7,  149, 2),
  ('Gold Plan',     '1 Month', 30,  259, 3),
  ('Platinum Plan', '6 Months',180, 599, 4),
  ('Diamond Plan',  '1 Year', 365,  999, 5);

alter table public.subscription_plans enable row level security;
create policy "anyone reads active plans" on public.subscription_plans for select using (active or public.is_admin());
create policy "owner manages plans"       on public.subscription_plans for all using (public.is_owner()) with check (public.is_owner());

-- ---------------------------------------------------------------------
-- 4. SUBSCRIPTIONS (one row per user; only server functions write it)
-- ---------------------------------------------------------------------
create table public.subscriptions (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  user_email text,
  plan_id    uuid references public.subscription_plans(id),
  status     text not null check (status in ('active', 'expired')),
  expires_at timestamptz not null,
  updated_at timestamptz not null default now()
);

alter table public.subscriptions enable row level security;
create policy "user reads own subscription" on public.subscriptions for select using (user_id = auth.uid() or public.is_admin());
-- No insert/update/delete policies: customers can NEVER write here.

create or replace function public.has_active_subscription()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.subscriptions
    where user_id = auth.uid() and status = 'active' and expires_at > now()
  )
$$;

-- Flips passed subscriptions to 'expired'. Scheduled below (pg_cron) and also
-- run whenever an admin opens the dashboard.
create or replace function public.expire_subscriptions()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update public.subscriptions set status = 'expired', updated_at = now()
  where status = 'active' and expires_at <= now();
  get diagnostics n = row_count;
  return n;
end $$;

-- ---------------------------------------------------------------------
-- 5. PAYMENTS (manual UPI + UTR verification)
-- ---------------------------------------------------------------------
create table public.payments (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  user_email  text,
  plan_id     uuid not null references public.subscription_plans(id),
  plan_name   text,
  amount      numeric(10,2),
  currency    text,
  reference   text not null unique,
  status      text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  admin_note  text,
  created_at  timestamptz not null default now(),
  reviewed_at timestamptz,
  reviewed_by text
);
-- Only one pending payment per user at a time.
create unique index payments_one_pending_per_user on public.payments (user_id) where status = 'pending';

-- Whatever the client sends, price/email/status come from the server.
create or replace function public.payments_before_insert()
returns trigger language plpgsql security definer set search_path = public as $$
declare p public.subscription_plans;
begin
  select * into p from public.subscription_plans where id = new.plan_id and active;
  if not found then raise exception 'This plan is not available'; end if;
  new.reference   := upper(regexp_replace(new.reference, '\s', '', 'g'));
  if new.reference !~ '^[A-Z0-9]{6,35}$' then
    raise exception 'Reference number should be 6-35 letters/digits (check your UPI app)';
  end if;
  new.user_id     := auth.uid();
  new.user_email  := auth.jwt() ->> 'email';
  new.plan_name   := p.name;
  new.amount      := p.price;
  new.currency    := p.currency;
  new.status      := 'pending';
  new.admin_note  := null;
  new.reviewed_at := null;
  new.reviewed_by := null;
  new.created_at  := now();
  return new;
end $$;
create trigger payments_before_insert before insert on public.payments
  for each row execute function public.payments_before_insert();

alter table public.payments enable row level security;
create policy "user inserts own pending payment" on public.payments for insert
  with check (auth.uid() is not null and user_id = auth.uid() and status = 'pending');
create policy "user reads own payments" on public.payments for select
  using (user_id = auth.uid() or public.is_admin());
create policy "admins update payments" on public.payments for update
  using (public.is_admin()) with check (public.is_admin());

-- Customer submits a UTR. Friendly errors for duplicates.
create or replace function public.submit_payment(p_plan_id uuid, p_reference text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if exists (select 1 from public.payments where user_id = auth.uid() and status = 'pending') then
    raise exception 'You already have a payment waiting for verification';
  end if;
  if exists (select 1 from public.payments where reference = upper(regexp_replace(p_reference, '\s', '', 'g'))) then
    raise exception 'This reference number has already been submitted';
  end if;
  insert into public.payments (plan_id, reference) values (p_plan_id, p_reference) returning * into r;
  return r;
end $$;

-- Admin approves. Only place in the whole system that grants a subscription.
create or replace function public.approve_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay  public.payments;
  plan public.subscription_plans;
  cur  public.subscriptions;
  base timestamptz;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;

  -- Atomic "still pending?" check: a double tap cannot approve twice.
  update public.payments
     set status = 'approved', reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'pending'
  returning * into pay;
  if not found then raise exception 'This payment was already processed'; end if;

  select * into plan from public.subscription_plans where id = pay.plan_id;
  select * into cur  from public.subscriptions where user_id = pay.user_id for update;

  -- new_expiry = max(now, current active expiry) + plan days (keeps unused time)
  base := greatest(now(), coalesce(case when cur.status = 'active' then cur.expires_at end, now()));

  insert into public.subscriptions (user_id, user_email, plan_id, status, expires_at, updated_at)
  values (pay.user_id, pay.user_email, pay.plan_id, 'active', base + make_interval(days => plan.duration_days), now())
  on conflict (user_id) do update
     set plan_id = excluded.plan_id, user_email = excluded.user_email, status = 'active',
         expires_at = excluded.expires_at, updated_at = now();
  return pay;
end $$;

create or replace function public.reject_payment(p_payment_id uuid, p_note text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if coalesce(length(trim(p_note)), 0) < 3 then raise exception 'Please type a short reason'; end if;
  update public.payments
     set status = 'rejected', admin_note = trim(p_note), reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'pending'
  returning * into pay;
  if not found then raise exception 'This payment was already processed'; end if;
  return pay;
end $$;

-- ---------------------------------------------------------------------
-- 6. CONTENT: channels, titles, episodes
-- ---------------------------------------------------------------------
create table public.channels (
  id          uuid primary key default gen_random_uuid(),
  name        text not null,
  description text not null default '',
  is_premium  boolean not null default false,
  sort_order  int not null default 0,
  created_at  timestamptz not null default now()
);
alter table public.channels enable row level security;
create policy "anyone reads channels" on public.channels for select using (true);
create policy "admins manage channels" on public.channels for all using (public.is_admin()) with check (public.is_admin());

create table public.titles (
  id               uuid primary key default gen_random_uuid(),
  kind             text not null check (kind in ('movie', 'series')),
  name             text not null,
  description      text not null default '',
  channel_id       uuid references public.channels(id) on delete set null,
  cover_path       text,              -- in public bucket "images"
  trailer_path     text,              -- in private bucket "videos"
  video_path       text,              -- in private bucket "videos" (movies)
  duration_seconds int,
  tier             text not null default 'free' check (tier in ('free', 'premium', 'hidden_premium')),
  published        boolean not null default false,   -- new uploads are drafts
  featured         boolean not null default false,
  view_count       bigint not null default 0,
  preview_count    bigint not null default 0,
  created_at       timestamptz not null default now()
);
alter table public.titles enable row level security;
-- Drafts: admins only. Hidden premium: subscribers (and admins) only.
create policy "visible titles" on public.titles for select using (
  public.is_admin()
  or (published and (tier <> 'hidden_premium' or public.has_active_subscription()))
);
create policy "admins manage titles" on public.titles for all using (public.is_admin()) with check (public.is_admin());

create table public.episodes (
  id               uuid primary key default gen_random_uuid(),
  title_id         uuid not null references public.titles(id) on delete cascade,
  episode_number   int not null,
  name             text not null,
  description      text not null default '',
  video_path       text not null,
  duration_seconds int,
  created_at       timestamptz not null default now(),
  unique (title_id, episode_number)
);
alter table public.episodes enable row level security;
create policy "episodes of visible titles" on public.episodes for select using (
  exists (select 1 from public.titles t where t.id = title_id)   -- titles RLS applies here
);
create policy "admins manage episodes" on public.episodes for all using (public.is_admin()) with check (public.is_admin());

-- ---------------------------------------------------------------------
-- 7. MARKETING CAMPAIGNS
-- ---------------------------------------------------------------------
create table public.campaigns (
  id         uuid primary key default gen_random_uuid(),
  name       text not null,
  message    text not null default '',
  active     boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.campaigns enable row level security;
create policy "anyone reads active campaigns" on public.campaigns for select using (active or public.is_admin());
create policy "admins manage campaigns" on public.campaigns for all using (public.is_admin()) with check (public.is_admin());

-- ---------------------------------------------------------------------
-- 8. VIEW COUNTERS + ADMIN STATS
-- ---------------------------------------------------------------------
create or replace function public.record_view(p_title_id uuid, p_preview boolean)
returns void language sql security definer set search_path = public as $$
  update public.titles
     set view_count    = view_count    + case when p_preview then 0 else 1 end,
         preview_count = preview_count + case when p_preview then 1 else 0 end
   where id = p_title_id and published;
$$;

create or replace function public.admin_stats()
returns json language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  perform public.expire_subscriptions();
  return json_build_object(
    'total_videos',        (select count(*) from public.titles),
    'exclusive_videos',    (select count(*) from public.titles where tier = 'hidden_premium'),
    'total_views',         (select coalesce(sum(view_count), 0) from public.titles),
    'preview_views',       (select coalesce(sum(preview_count), 0) from public.titles),
    'paying_subscribers',  (select count(*) from public.subscriptions where status = 'active' and expires_at > now()),
    'expired_subscribers', (select count(*) from public.subscriptions where status = 'expired' or expires_at <= now()),
    'pending_payments',    (select count(*) from public.payments where status = 'pending')
  );
end $$;

-- ---------------------------------------------------------------------
-- 9. STORAGE: "images" is public, "videos" is private
-- ---------------------------------------------------------------------
insert into storage.buckets (id, name, public) values ('images', 'images', true)  on conflict do nothing;
insert into storage.buckets (id, name, public) values ('videos', 'videos', false) on conflict do nothing;

-- Who may stream a given file in the "videos" bucket?
--   trailers of visible titles ............ everyone
--   free title videos ..................... everyone
--   premium videos ........................ subscribers; others only if the title has
--                                           no trailer (the app stops at 30 seconds)
--   hidden premium ........................ subscribers only (titles RLS hides it)
create or replace function public.can_stream(object_name text)
returns boolean language plpgsql stable security definer set search_path = public as $$
declare t public.titles;
begin
  if public.is_admin() then return true; end if;

  select * into t from public.titles
   where published and (trailer_path = object_name or video_path = object_name)
   limit 1;
  if not found then
    select tt.* into t from public.episodes e join public.titles tt on tt.id = e.title_id
     where tt.published and e.video_path = object_name limit 1;
    if not found then return false; end if;
  end if;

  if t.tier = 'hidden_premium' then return public.has_active_subscription(); end if;
  if t.trailer_path = object_name then return true; end if;
  if t.tier = 'free' then return true; end if;
  return public.has_active_subscription() or t.trailer_path is null;
end $$;

create policy "images public read" on storage.objects for select using (bucket_id = 'images');
create policy "admins write images" on storage.objects for insert with check (bucket_id = 'images' and public.is_admin());
create policy "admins update images" on storage.objects for update using (bucket_id = 'images' and public.is_admin());
create policy "admins delete images" on storage.objects for delete using (bucket_id = 'images' and public.is_admin());

create policy "stream videos" on storage.objects for select using (bucket_id = 'videos' and public.can_stream(name));
create policy "admins write videos" on storage.objects for insert with check (bucket_id = 'videos' and public.is_admin());
create policy "admins update videos" on storage.objects for update using (bucket_id = 'videos' and public.is_admin());
create policy "admins delete videos" on storage.objects for delete using (bucket_id = 'videos' and public.is_admin());

-- ---------------------------------------------------------------------
-- 10. OWNER ACCOUNT  ← CHANGE THIS EMAIL
-- ---------------------------------------------------------------------
insert into public.admin_emails (email, role) values (lower('YOUR_OWNER_EMAIL@example.com'), 'owner');

-- ---------------------------------------------------------------------
-- 11. (Optional) nightly expiry sweep. Enable "pg_cron" first under
--     Database → Extensions, then run these two lines separately:
-- ---------------------------------------------------------------------
-- create extension if not exists pg_cron;
-- select cron.schedule('expire-subscriptions', '*/30 * * * *', 'select public.expire_subscriptions()');
