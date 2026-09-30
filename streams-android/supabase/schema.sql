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

-- Who may stream a given file in the "videos" bucket? (never guests)
--   trailers of visible titles ............ any signed-in user
--   free title videos ..................... any signed-in user
--   premium videos ........................ subscribers; others only if the title has
--                                           no trailer (the app stops at 30 seconds)
--   hidden premium ........................ subscribers only (titles RLS hides it)
create or replace function public.can_stream(object_name text)
returns boolean language plpgsql stable security definer set search_path = public as $$
declare t public.titles;
begin
  if public.is_admin() then return true; end if;
  -- Browsing is open to everyone, but watching anything needs an account.
  if auth.uid() is null then return false; end if;

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
-- 11. FUNCTION ACCESS — admin/payment functions are not callable by guests,
--     internal ones are not callable through the API at all.
-- ---------------------------------------------------------------------
revoke execute on function public.admin_stats() from public, anon;
revoke execute on function public.approve_payment(uuid) from public, anon;
revoke execute on function public.reject_payment(uuid, text) from public, anon;
revoke execute on function public.submit_payment(uuid, text) from public, anon;
grant execute on function public.admin_stats() to authenticated;
grant execute on function public.approve_payment(uuid) to authenticated;
grant execute on function public.reject_payment(uuid, text) to authenticated;
grant execute on function public.submit_payment(uuid, text) to authenticated;
revoke execute on function public.expire_subscriptions() from public, anon, authenticated;
revoke execute on function public.payments_before_insert() from public, anon, authenticated;

-- ---------------------------------------------------------------------
-- 12. Expiry sweep every 30 minutes (pg_cron is available on all Supabase plans).
-- ---------------------------------------------------------------------
create extension if not exists pg_cron;
select cron.schedule('expire-subscriptions', '*/30 * * * *', 'select public.expire_subscriptions()');

-- ---------------------------------------------------------------------
-- 13. PREMIUM CLOUD STORAGE — each member gets a private folder
--     user-files/<user id>/... Uploads need an active plan and stay under
--     app_settings.cloud_quota_gb. Deleting always works (to free space).
-- ---------------------------------------------------------------------
alter table public.app_settings add column if not exists cloud_quota_gb int not null default 2048 check (cloud_quota_gb > 0);

insert into storage.buckets (id, name, public) values ('user-files', 'user-files', false) on conflict do nothing;

create or replace function public.cloud_quota_bytes()
returns bigint language sql stable security definer set search_path = public as $$
  select cloud_quota_gb::bigint * 1024 * 1024 * 1024 from public.app_settings where id = 1
$$;

create or replace function public.my_cloud_usage()
returns bigint language sql stable security definer set search_path = public as $$
  select coalesce(sum((o.metadata ->> 'size')::bigint), 0)::bigint
  from storage.objects o
  where o.bucket_id = 'user-files' and (storage.foldername(o.name))[1] = auth.uid()::text
$$;

create or replace function public.my_files()
returns table (name text, size bigint, mimetype text, created_at timestamptz)
language sql stable security definer set search_path = public as $$
  select o.name, coalesce((o.metadata ->> 'size')::bigint, 0), o.metadata ->> 'mimetype', o.created_at
  from storage.objects o
  where auth.uid() is not null
    and o.bucket_id = 'user-files' and (storage.foldername(o.name))[1] = auth.uid()::text
  order by o.created_at desc
$$;

revoke execute on function public.my_cloud_usage() from public, anon;
revoke execute on function public.my_files() from public, anon;
grant execute on function public.my_cloud_usage() to authenticated;
grant execute on function public.my_files() to authenticated;

create policy "cloud: read own files" on storage.objects for select
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "cloud: premium uploads within quota" on storage.objects for insert
  with check (
    bucket_id = 'user-files'
    and (storage.foldername(name))[1] = auth.uid()::text
    and public.has_active_subscription()
    and public.my_cloud_usage() < public.cloud_quota_bytes()
  );
create policy "cloud: delete own files" on storage.objects for delete
  using (bucket_id = 'user-files' and (storage.foldername(name))[1] = auth.uid()::text);

-- ---------------------------------------------------------------------
-- 14. AUTOMATIC UPI CONFIRMATION (no payment gateway)
--     1. App calls start_upi_payment(plan) → an 'initiated' row with our ref.
--     2. App opens the UPI app; the UPI app hands back a response string
--        (txnId, ApprovalRefNo/UTR, Status).
--     3. App calls confirm_upi_payment(id, response) → if Status=SUCCESS the
--        plan is applied immediately and the row is marked approved, method 'upi_auto'.
--     NOTE: that response comes from the customer's phone, so it cannot be
--     proven genuine. Admins match auto payments against the bank statement
--     and can revoke_payment() one that never arrived (takes the days back).
-- ---------------------------------------------------------------------
alter table public.payments add column if not exists method       text not null default 'manual';
alter table public.payments add column if not exists txn_id       text;
alter table public.payments add column if not exists upi_response text;
create unique index if not exists payments_txn_id_unique on public.payments (upper(txn_id)) where txn_id is not null;
alter table public.payments drop constraint if exists payments_status_check;
alter table public.payments add constraint payments_status_check
  check (status in ('initiated', 'pending', 'approved', 'rejected', 'revoked'));
alter table public.payments drop constraint if exists payments_method_check;
alter table public.payments add constraint payments_method_check check (method in ('manual', 'upi_auto'));

-- Client inserts are forced to a harmless state: a manual 'pending' UTR claim,
-- or an 'initiated' auto payment (which only confirm_upi_payment can complete).
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
  new.status      := case when new.status = 'initiated' then 'initiated' else 'pending' end;
  new.method      := case when new.status = 'initiated' then 'upi_auto' else 'manual' end;
  new.txn_id      := null;
  new.upi_response := null;
  new.admin_note  := null;
  new.reviewed_at := null;
  new.reviewed_by := null;
  new.created_at  := now();
  return new;
end $$;

drop policy if exists "user inserts own pending payment" on public.payments;
create policy "user inserts own pending payment" on public.payments for insert
  with check (auth.uid() is not null and user_id = auth.uid() and status in ('pending', 'initiated'));

-- Shared by manual approval and automatic confirmation.
-- new_expiry = max(now, current active expiry) + plan days (keeps unused time)
create or replace function public.grant_plan_for_payment(pay public.payments)
returns void language plpgsql security definer set search_path = public as $$
declare
  plan public.subscription_plans;
  cur  public.subscriptions;
  base timestamptz;
begin
  select * into plan from public.subscription_plans where id = pay.plan_id;
  select * into cur  from public.subscriptions where user_id = pay.user_id for update;
  base := greatest(now(), coalesce(case when cur.status = 'active' then cur.expires_at end, now()));
  insert into public.subscriptions (user_id, user_email, plan_id, status, expires_at, updated_at)
  values (pay.user_id, pay.user_email, pay.plan_id, 'active', base + make_interval(days => plan.duration_days), now())
  on conflict (user_id) do update
     set plan_id = excluded.plan_id, user_email = excluded.user_email, status = 'active',
         expires_at = excluded.expires_at, updated_at = now();
end $$;

create or replace function public.approve_payment(p_payment_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare pay public.payments;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  -- Atomic "still pending?" check: a double tap cannot approve twice.
  update public.payments
     set status = 'approved', reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'pending'
  returning * into pay;
  if not found then raise exception 'This payment was already processed'; end if;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

-- Step 1: the app asks for a fresh payment reference before opening the UPI app.
create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  -- An unfinished earlier attempt never granted anything, so it can go.
  delete from public.payments where user_id = auth.uid() and status = 'initiated';
  insert into public.payments (plan_id, reference, status)
  values (p_plan_id, 'STR' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 14)), 'initiated')
  returning * into r;
  return r;
end $$;

-- Step 2: the app forwards the UPI app's response. SUCCESS → plan applied now.
create or replace function public.confirm_upi_payment(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  kv     text;
  f      jsonb := '{}';
  st     text;
  txn    text;
  pay    public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if coalesce(length(p_response), 0) = 0 or length(p_response) > 2000 then
    raise exception 'No response from the UPI app';
  end if;

  -- "txnId=..&responseCode=00&Status=SUCCESS&txnRef=..&ApprovalRefNo=.." (key case varies by app)
  foreach kv in array string_to_array(p_response, '&') loop
    if position('=' in kv) > 0 then
      f := f || jsonb_build_object(lower(trim(split_part(kv, '=', 1))), trim(substr(kv, position('=' in kv) + 1)));
    end if;
  end loop;

  st := lower(coalesce(f ->> 'status', ''));
  if st <> 'success' then
    raise exception 'The UPI app did not report a successful payment (status: %)', coalesce(nullif(st, ''), 'unknown');
  end if;
  txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
  if txn !~ '^[A-Z0-9]{6,40}$' then raise exception 'The UPI app did not return a transaction ID'; end if;
  if exists (select 1 from public.payments where upper(txn_id) = txn or reference = txn) then
    raise exception 'This transaction has already been used';
  end if;

  update public.payments
     set status = 'approved', method = 'upi_auto', txn_id = txn, upi_response = left(p_response, 2000),
         reviewed_at = now(), reviewed_by = 'auto (UPI app)'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
     and created_at > now() - interval '30 minutes'
  returning * into pay;
  if not found then raise exception 'This payment has expired or was already processed. Please start again.'; end if;

  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

-- Admin: take back an approved payment whose money never arrived.
create or replace function public.revoke_payment(p_payment_id uuid, p_note text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay  public.payments;
  plan public.subscription_plans;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if coalesce(length(trim(p_note)), 0) < 3 then raise exception 'Please type a short reason'; end if;
  update public.payments
     set status = 'revoked', admin_note = trim(p_note), reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'approved'
  returning * into pay;
  if not found then raise exception 'Only approved payments can be revoked'; end if;

  select * into plan from public.subscription_plans where id = pay.plan_id;
  update public.subscriptions
     set expires_at = expires_at - make_interval(days => plan.duration_days), updated_at = now()
   where user_id = pay.user_id;
  update public.subscriptions
     set status = 'expired', updated_at = now()
   where user_id = pay.user_id and status = 'active' and expires_at <= now();
  return pay;
end $$;

-- Abandoned attempts (the customer backed out of the UPI app) are cleaned up by the sweep.
create or replace function public.expire_subscriptions()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update public.subscriptions set status = 'expired', updated_at = now()
  where status = 'active' and expires_at <= now();
  get diagnostics n = row_count;
  delete from public.payments where status = 'initiated' and created_at < now() - interval '1 day';
  return n;
end $$;

revoke execute on function public.grant_plan_for_payment(public.payments) from public, anon, authenticated;
revoke execute on function public.start_upi_payment(uuid) from public, anon;
revoke execute on function public.confirm_upi_payment(uuid, text) from public, anon;
revoke execute on function public.revoke_payment(uuid, text) from public, anon;
grant execute on function public.start_upi_payment(uuid) to authenticated;
grant execute on function public.confirm_upi_payment(uuid, text) to authenticated;
grant execute on function public.revoke_payment(uuid, text) to authenticated;
revoke execute on function public.expire_subscriptions() from public, anon, authenticated;

-- A UTR that was already confirmed automatically can't be claimed again by hand.
create or replace function public.submit_payment(p_plan_id uuid, p_reference text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  r   public.payments;
  ref text := upper(regexp_replace(p_reference, '\s', '', 'g'));
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if exists (select 1 from public.payments where user_id = auth.uid() and status = 'pending') then
    raise exception 'You already have a payment waiting for verification';
  end if;
  if exists (select 1 from public.payments where reference = ref or upper(txn_id) = ref) then
    raise exception 'This reference number has already been submitted';
  end if;
  insert into public.payments (plan_id, reference) values (p_plan_id, p_reference) returning * into r;
  return r;
end $$;

-- ---------------------------------------------------------------------
-- 15. HOURLY PLANS — a plan lasts duration_days + duration_hours.
-- ---------------------------------------------------------------------
alter table public.subscription_plans add column if not exists duration_hours int not null default 0;
alter table public.subscription_plans drop constraint if exists subscription_plans_duration_days_check;
alter table public.subscription_plans drop constraint if exists subscription_plans_duration_check;
alter table public.subscription_plans add constraint subscription_plans_duration_check
  check (duration_days >= 0 and duration_hours >= 0 and (duration_days > 0 or duration_hours > 0));

insert into public.subscription_plans (name, duration_label, duration_days, duration_hours, price, sort_order)
select '1 Hour Pass', '1 Hour', 0, 1, 1, 0
where not exists (select 1 from public.subscription_plans where name = '1 Hour Pass');

create or replace function public.plan_interval(plan public.subscription_plans)
returns interval language sql immutable as $$
  select make_interval(days => plan.duration_days, hours => plan.duration_hours)
$$;

-- new_expiry = max(now, current active expiry) + plan length (keeps unused time)
create or replace function public.grant_plan_for_payment(pay public.payments)
returns void language plpgsql security definer set search_path = public as $$
declare
  plan public.subscription_plans;
  cur  public.subscriptions;
  base timestamptz;
begin
  select * into plan from public.subscription_plans where id = pay.plan_id;
  select * into cur  from public.subscriptions where user_id = pay.user_id for update;
  base := greatest(now(), coalesce(case when cur.status = 'active' then cur.expires_at end, now()));
  insert into public.subscriptions (user_id, user_email, plan_id, status, expires_at, updated_at)
  values (pay.user_id, pay.user_email, pay.plan_id, 'active', base + public.plan_interval(plan), now())
  on conflict (user_id) do update
     set plan_id = excluded.plan_id, user_email = excluded.user_email, status = 'active',
         expires_at = excluded.expires_at, updated_at = now();
end $$;

create or replace function public.revoke_payment(p_payment_id uuid, p_note text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay  public.payments;
  plan public.subscription_plans;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if coalesce(length(trim(p_note)), 0) < 3 then raise exception 'Please type a short reason'; end if;
  update public.payments
     set status = 'revoked', admin_note = trim(p_note), reviewed_at = now(), reviewed_by = auth.jwt() ->> 'email'
   where id = p_payment_id and status = 'approved'
  returning * into pay;
  if not found then raise exception 'Only approved payments can be revoked'; end if;
  select * into plan from public.subscription_plans where id = pay.plan_id;
  update public.subscriptions
     set expires_at = expires_at - public.plan_interval(plan), updated_at = now()
   where user_id = pay.user_id;
  update public.subscriptions
     set status = 'expired', updated_at = now()
   where user_id = pay.user_id and status = 'active' and expires_at <= now();
  return pay;
end $$;

revoke execute on function public.grant_plan_for_payment(public.payments) from public, anon, authenticated;

-- ---------------------------------------------------------------------
-- 16. BANK-CREDIT AUTO VERIFICATION (no gateway)
--     GPay/PhonePe usually return nothing to the app for payments to a personal
--     UPI ID, so the app can't learn the payment happened. Instead:
--       * every order gets a unique amount (plan price + 1..99 paise), valid 60 min;
--       * the owner's phone (Streams app, signed in as admin, "Auto-verify" on)
--         reads the bank's "credited" SMS / UPI app notification and calls
--         record_bank_credit(amount, ref);
--       * the server finds the open order with exactly that amount and activates it.
--     The customer's app polls its order and shows success as soon as it flips.
-- ---------------------------------------------------------------------
create table if not exists public.bank_credits (
  id          uuid primary key default gen_random_uuid(),
  amount      numeric(10,2) not null,
  ref         text,
  source      text,
  raw         text,
  payment_id  uuid references public.payments(id) on delete set null,
  received_at timestamptz not null default now(),
  reported_by text
);
create unique index if not exists bank_credits_ref_unique on public.bank_credits (ref) where ref is not null;
alter table public.bank_credits enable row level security;
drop policy if exists "admins read bank credits" on public.bank_credits;
create policy "admins read bank credits" on public.bank_credits for select using (public.is_admin());

alter table public.payments add column if not exists bank_credit_id uuid references public.bank_credits(id) on delete set null;

-- Step 1: order with a unique amount so an incoming credit identifies it.
create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  r     public.payments;
  price numeric(10,2);
  amt   numeric(10,2);
  tries int := 0;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  delete from public.payments where user_id = auth.uid() and status = 'initiated';
  select p.price into price from public.subscription_plans p where p.id = p_plan_id and p.active;
  if not found then raise exception 'This plan is not available'; end if;
  loop
    tries := tries + 1;
    amt := price + (1 + floor(random() * 99))::int / 100.0;
    -- Unique among open orders, and among auto orders still waiting for their bank credit.
    exit when not exists (
      select 1 from public.payments
       where amount = amt
         and ((status = 'initiated' and created_at > now() - interval '60 minutes')
           or (status = 'approved' and method = 'upi_auto' and bank_credit_id is null
               and created_at > now() - interval '120 minutes')));
    if tries > 200 then raise exception 'Too many payments in progress, please try again in a minute'; end if;
  end loop;
  insert into public.payments (plan_id, reference, status)
  values (p_plan_id, 'STR' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 14)), 'initiated')
  returning * into r;
  update public.payments set amount = amt where id = r.id returning * into r;
  return r;
end $$;

-- Step 2 (owner's phone): a credit arrived. Match it to the open order with that amount.
create or replace function public.record_bank_credit(p_amount numeric, p_ref text, p_source text, p_raw text)
returns json language plpgsql security definer set search_path = public as $$
declare
  c    public.bank_credits;
  pay  public.payments;
  v_ref text := nullif(upper(regexp_replace(coalesce(p_ref, ''), '\s', '', 'g')), '');
  amt  numeric(10,2) := round(p_amount, 2);
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if amt is null or amt <= 0 then raise exception 'Bad amount'; end if;

  -- The same credit often arrives twice (bank SMS + UPI app notification).
  select * into c from public.bank_credits
   where (v_ref is not null and bank_credits.ref = v_ref)
      or (bank_credits.amount = amt and bank_credits.received_at > now() - interval '5 minutes'
          and (v_ref is null or bank_credits.ref is null))
   order by received_at desc limit 1 for update;
  if found then
    if c.ref is null and v_ref is not null then
      update public.bank_credits set ref = v_ref where id = c.id returning * into c;
      update public.payments set txn_id = coalesce(txn_id, v_ref) where id = c.payment_id
        and not exists (select 1 from public.payments p2 where upper(p2.txn_id) = v_ref);
    end if;
    return json_build_object('duplicate', true, 'payment_id', c.payment_id);
  end if;

  insert into public.bank_credits (amount, ref, source, raw, reported_by)
  values (amt, v_ref, left(p_source, 80), left(p_raw, 500), auth.jwt() ->> 'email')
  returning * into c;

  -- Open order with exactly this amount → activate it.
  update public.payments
     set status = 'approved', method = 'upi_auto', bank_credit_id = c.id,
         txn_id = case when v_ref is not null and not exists (select 1 from public.payments p2 where upper(p2.txn_id) = v_ref) then v_ref end,
         reviewed_at = now(), reviewed_by = 'auto (bank credit)'
   where id = (select id from public.payments
                where status = 'initiated' and amount = amt and created_at > now() - interval '60 minutes'
                order by created_at desc limit 1 for update skip locked)
  returning * into pay;
  if found then
    perform public.grant_plan_for_payment(pay);
  else
    -- Already activated from the UPI app's own response? Mark it bank-verified.
    update public.payments set bank_credit_id = c.id
     where id = (select id from public.payments
                  where status = 'approved' and method = 'upi_auto' and bank_credit_id is null
                    and amount = amt and created_at > now() - interval '120 minutes'
                  order by created_at desc limit 1)
    returning * into pay;
  end if;
  if pay.id is not null then update public.bank_credits set payment_id = pay.id where id = c.id; end if;
  return json_build_object('duplicate', false, 'payment_id', pay.id, 'plan', pay.plan_name, 'email', pay.user_email);
end $$;

-- Confirmation from the UPI app's response (when an app does send one): 60-minute window.
create or replace function public.confirm_upi_payment(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  kv     text;
  f      jsonb := '{}';
  st     text;
  txn    text;
  pay    public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if coalesce(length(p_response), 0) = 0 or length(p_response) > 2000 then
    raise exception 'No response from the UPI app';
  end if;
  foreach kv in array string_to_array(p_response, '&') loop
    if position('=' in kv) > 0 then
      f := f || jsonb_build_object(lower(trim(split_part(kv, '=', 1))), trim(substr(kv, position('=' in kv) + 1)));
    end if;
  end loop;
  st := lower(coalesce(f ->> 'status', ''));
  if st <> 'success' then
    raise exception 'The UPI app did not report a successful payment (status: %)', coalesce(nullif(st, ''), 'unknown');
  end if;
  txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
  if txn !~ '^[A-Z0-9]{6,40}$' then raise exception 'The UPI app did not return a transaction ID'; end if;
  if exists (select 1 from public.payments where upper(txn_id) = txn or reference = txn) then
    raise exception 'This transaction has already been used';
  end if;
  update public.payments
     set status = 'approved', method = 'upi_auto', txn_id = txn, upi_response = left(p_response, 2000),
         reviewed_at = now(), reviewed_by = 'auto (UPI app)'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
     and created_at > now() - interval '60 minutes'
  returning * into pay;
  if not found then raise exception 'This payment has expired or was already processed. Please start again.'; end if;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

revoke execute on function public.record_bank_credit(numeric, text, text, text) from public, anon;
grant execute on function public.record_bank_credit(numeric, text, text, text) to authenticated;

-- ---------------------------------------------------------------------
-- 17. EXACT PLAN PRICE (no extra paise). Orders are charged exactly the plan
--     price; an incoming credit activates the most recent open order with that
--     amount from the last 20 minutes. If two customers buy the same plan in the
--     same few minutes, each credit activates one of them (the credit is marked
--     ambiguous so the owner can double-check); anyone left waiting can still
--     submit their UTR.
-- ---------------------------------------------------------------------
alter table public.bank_credits add column if not exists ambiguous boolean not null default false;

create or replace function public.start_upi_payment(p_plan_id uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare r public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  delete from public.payments where user_id = auth.uid() and status = 'initiated';
  insert into public.payments (plan_id, reference, status)
  values (p_plan_id, 'STR' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 14)), 'initiated')
  returning * into r;   -- amount = plan price (set by payments_before_insert)
  return r;
end $$;

create or replace function public.record_bank_credit(p_amount numeric, p_ref text, p_source text, p_raw text)
returns json language plpgsql security definer set search_path = public as $$
declare
  c     public.bank_credits;
  pay   public.payments;
  v_ref text := nullif(upper(regexp_replace(coalesce(p_ref, ''), '\s', '', 'g')), '');
  amt   numeric(10,2) := round(p_amount, 2);
  open_count int;
begin
  if not public.is_admin() then raise exception 'Not allowed'; end if;
  if amt is null or amt <= 0 then raise exception 'Bad amount'; end if;

  -- The same credit often arrives twice (bank SMS + UPI app notification).
  select * into c from public.bank_credits
   where (v_ref is not null and bank_credits.ref = v_ref)
      -- same money reported once by SMS and once by an app notification (never two SMS / two alerts)
      or (bank_credits.amount = amt and bank_credits.received_at > now() - interval '3 minutes'
          and (v_ref is null or bank_credits.ref is null)
          and split_part(bank_credits.source, ':', 1) <> split_part(coalesce(p_source, ''), ':', 1))
   order by received_at desc limit 1 for update;
  if found then
    if c.ref is null and v_ref is not null then
      update public.bank_credits set ref = v_ref where id = c.id returning * into c;
      update public.payments set txn_id = coalesce(txn_id, v_ref) where id = c.payment_id
        and not exists (select 1 from public.payments p2 where upper(p2.txn_id) = v_ref);
    end if;
    return json_build_object('duplicate', true, 'payment_id', c.payment_id);
  end if;

  select count(*) into open_count from public.payments
   where status = 'initiated' and amount = amt and created_at > now() - interval '20 minutes';

  insert into public.bank_credits (amount, ref, source, raw, reported_by, ambiguous)
  values (amt, v_ref, left(p_source, 80), left(p_raw, 500), auth.jwt() ->> 'email', open_count > 1)
  returning * into c;

  update public.payments
     set status = 'approved', method = 'upi_auto', bank_credit_id = c.id,
         txn_id = case when v_ref is not null and not exists (select 1 from public.payments p2 where upper(p2.txn_id) = v_ref) then v_ref end,
         reviewed_at = now(), reviewed_by = 'auto (bank credit)'
   where id = (select id from public.payments
                where status = 'initiated' and amount = amt and created_at > now() - interval '20 minutes'
                order by created_at desc limit 1 for update skip locked)
  returning * into pay;
  if found then
    perform public.grant_plan_for_payment(pay);
  else
    -- Already activated from the UPI app's own response? Mark it bank-verified.
    update public.payments set bank_credit_id = c.id
     where id = (select id from public.payments
                  where status = 'approved' and method = 'upi_auto' and bank_credit_id is null
                    and amount = amt and created_at > now() - interval '60 minutes'
                  order by created_at desc limit 1)
    returning * into pay;
  end if;
  if pay.id is not null then update public.bank_credits set payment_id = pay.id where id = c.id; end if;
  return json_build_object('duplicate', false, 'payment_id', pay.id, 'plan', pay.plan_name,
                           'email', pay.user_email, 'ambiguous', open_count > 1);
end $$;

-- Orders stay open 20 minutes (matches the bank-credit window).
create or replace function public.confirm_upi_payment(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  kv     text;
  f      jsonb := '{}';
  st     text;
  txn    text;
  pay    public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if coalesce(length(p_response), 0) = 0 or length(p_response) > 2000 then
    raise exception 'No response from the UPI app';
  end if;
  foreach kv in array string_to_array(p_response, '&') loop
    if position('=' in kv) > 0 then
      f := f || jsonb_build_object(lower(trim(split_part(kv, '=', 1))), trim(substr(kv, position('=' in kv) + 1)));
    end if;
  end loop;
  st := lower(coalesce(f ->> 'status', ''));
  if st <> 'success' then
    raise exception 'The UPI app did not report a successful payment (status: %)', coalesce(nullif(st, ''), 'unknown');
  end if;
  txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
  if txn !~ '^[A-Z0-9]{6,40}$' then raise exception 'The UPI app did not return a transaction ID'; end if;
  if exists (select 1 from public.payments where upper(txn_id) = txn or reference = txn) then
    raise exception 'This transaction has already been used';
  end if;
  update public.payments
     set status = 'approved', method = 'upi_auto', txn_id = txn, upi_response = left(p_response, 2000),
         reviewed_at = now(), reviewed_by = 'auto (UPI app)'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
     and created_at > now() - interval '20 minutes'
  returning * into pay;
  if not found then raise exception 'This payment has expired or was already processed. Please start again.'; end if;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

-- ---------------------------------------------------------------------
-- 18. MERCHANT UPI + FULL UPI APP RESULT
--     The UPI link carries tr (order number) and, if set, mc (merchant category
--     code). Whatever the UPI app returns - success, failure, pending - is saved.
-- ---------------------------------------------------------------------
alter table public.app_settings add column if not exists merchant_code text;

alter table public.payments drop constraint if exists payments_status_check;
alter table public.payments add constraint payments_status_check
  check (status in ('initiated', 'pending', 'approved', 'rejected', 'revoked', 'failed'));

-- Turns the UPI app's "k=v&k=v" response into json with lower-case keys.
create or replace function public.parse_upi_response(p_response text)
returns jsonb language plpgsql immutable as $$
declare kv text; f jsonb := '{}';
begin
  foreach kv in array string_to_array(coalesce(p_response, ''), '&') loop
    if position('=' in kv) > 0 then
      f := f || jsonb_build_object(lower(trim(split_part(kv, '=', 1))), trim(substr(kv, position('=' in kv) + 1)));
    end if;
  end loop;
  return f;
end $$;

-- SUCCESS → plan applied now. Also checks the response belongs to this order (txnRef).
create or replace function public.confirm_upi_payment(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  f   jsonb;
  st  text;
  txn text;
  pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if coalesce(length(p_response), 0) = 0 or length(p_response) > 2000 then
    raise exception 'No response from the UPI app';
  end if;
  f  := public.parse_upi_response(p_response);
  st := lower(coalesce(f ->> 'status', ''));
  if st <> 'success' then
    raise exception 'The UPI app did not report a successful payment (status: %)', coalesce(nullif(st, ''), 'unknown');
  end if;
  if nullif(f ->> 'txnref', '') is not null and exists (
       select 1 from public.payments where id = p_payment_id and upper(reference) <> upper(f ->> 'txnref')) then
    raise exception 'This payment response belongs to a different order';
  end if;
  txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
  if txn !~ '^[A-Z0-9]{6,40}$' then raise exception 'The UPI app did not return a transaction ID'; end if;
  if exists (select 1 from public.payments where upper(txn_id) = txn or reference = txn) then
    raise exception 'This transaction has already been used';
  end if;
  update public.payments
     set status = 'approved', method = 'upi_auto', txn_id = txn, upi_response = left(p_response, 2000),
         reviewed_at = now(), reviewed_by = 'auto (UPI app)'
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
     and created_at > now() - interval '20 minutes'
  returning * into pay;
  if not found then raise exception 'This payment has expired or was already processed. Please start again.'; end if;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;

-- Whatever the UPI app returned (success, failure, pending) is saved on the order.
--   SUCCESS          → approved, plan applied
--   FAILURE          → failed (shown to the customer and in admin)
--   SUBMITTED/other  → stays open; a bank credit can still activate it
create or replace function public.report_upi_result(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  st  text := lower(coalesce(public.parse_upi_response(p_response) ->> 'status', ''));
  pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  if st = 'success' then
    return public.confirm_upi_payment(p_payment_id, p_response);
  end if;
  update public.payments
     set upi_response = left(p_response, 2000),
         status       = case when st in ('failure', 'failed') then 'failed' else status end,
         reviewed_at  = case when st in ('failure', 'failed') then now() else reviewed_at end,
         reviewed_by  = case when st in ('failure', 'failed') then 'UPI app: payment failed' else reviewed_by end
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
  returning * into pay;
  if not found then select * into pay from public.payments where id = p_payment_id and user_id = auth.uid(); end if;
  return pay;
end $$;

revoke execute on function public.report_upi_result(uuid, text) from public, anon;
grant execute on function public.report_upi_result(uuid, text) to authenticated;
revoke execute on function public.parse_upi_response(text) from public, anon;
