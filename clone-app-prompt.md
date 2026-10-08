You are setting up an EXACT COPY of my existing app (currently called "Flixvault") under a NEW NAME, with a NEW GitHub repository and a NEW Supabase project.

THE MOST IMPORTANT RULE: do NOT rebuild, rewrite, redesign or "improve" anything. COPY the existing source code 1:1 and change ONLY the items in STEP 2. Every screen, every flow, the admin panel, the website, the database, the Meta ads tracking and above all the UPI PAYMENT FLOW must work exactly like the source. If something is unclear, stop and ask me. Never guess.

==================== MY DETAILS (I fill these in) ====================
NEW_APP_NAME                 = ________          (example: Moviebox)
NEW_SUPPORT_EMAIL            = ________          (example: info.moviebox@gmail.com)
NEW_GITHUB_REPO              = ________/________ (owner/repo: a new EMPTY repo)
NEW_SUPABASE_REF             = ________          (Supabase → Project Settings → General → Reference ID)
NEW_SUPABASE_PUBLISHABLE_KEY = sb_publishable_________
NEW_SUPABASE_SECRET_KEY      = sb_secret_________  (only for Vercel; NEVER commit it)
NEW_DIRECT_APP_ID            = com.________.app    (package of the APKs I share myself)
NEW_PLAY_APP_ID              = com.________.play   (package for Google Play; must differ from the one above)
NEW_UPI_ID                   = ________@____
NEW_UPI_PAYEE_NAME           = ________          (name shown in the UPI app)
OWNER_EMAIL                  = ________          (my admin panel login)
OWNER_PASSWORD               = ________
NEW_WEBSITE_URL              = https://________.vercel.app  (if not known yet, fill in after STEP 6)
=======================================================================

SOURCE (read-only: copy from it, NEVER push or open PRs on it):
- Repository: sahilchandpara008-pixel/Cloud-Storage
- Branch: claude/pensive-allen-07t7y7 (latest commit; at the time of writing 239f977)
If you cannot access the source repository, STOP and ask me to add it to this session. Do not recreate it from memory or from this description.

======================================================================
WHAT THE SOURCE CONTAINS (all of this must exist and work in the copy)
======================================================================
Use this list only to VERIFY the copy at the end. Do not build these yourself; they come with the copied code.

1. MOBILE APP (Flutter, folder mobile/)
   - Guest mode and login: email + password, and Continue with Google (deep link NEW_DIRECT_APP_ID://login-callback).
   - Consent / policy screen (policyVersion v2) with privacy, terms, refund, community rules, DMCA, contact.
   - Cloud storage: folders, upload photos/videos/files (50 MB per file limit), rename, delete, open, play.
   - Channels: explore, feed, join/leave, channel page, channel photo (DP) that the creator can change, creators upload posts from their profile, channel requests that the admin approves.
   - Netflix-style post page: separate Trailer and Play buttons, duration, description, "More like this" from the same uploader, Share, Channel, Report, Download (Premium).
   - Video player with MX Player/VLC-style rotation (screen rotation channel).
   - Premium downloads for cloud files and posts through Android DownloadManager (download channel).
   - In-app Report (post or channel, with reason and details) → admin Requests; Block / Unblock channel (stored on the phone).
   - Premium screen: 5 plans from the database → UPI payment.
   - UPI PAYMENT FLOW (must stay identical, see docs/09-upi-payments.md): Premium → pick plan → Next (login if guest) → create_payment_order (server sets the amount and reference) → app opens upi://pay?pa=UPI_ID&pn=PAYEE&tr=<ref>&am=<amount>&cu=INR through a chooser ("No UPI app found" if none) → MainActivity saves the UPI app's answer → report_payment_result sends it to the server with retries → server activates the plan only if the reference matches, a transaction id exists, that id was never used before and the order is under 2 hours old; otherwise it stays PENDING → status screen polls every 5 s → owner can "Mark as paid (UTR)" or "Revoke" in admin Payments → open orders older than 24 h are cancelled by cron every 30 min → payment history screen. Organic buyers become "pending" until the owner approves them.
   - Settings: contact support, policies, delete account (Edge Function delete-account).
   - Install attribution: organic vs ads (APK_REFERRER dart-define in the ads APK) and the install ↔ ad-click match.
   - Two Android flavors: "direct" (shared APKs, UPI only, no Google code) and "play" (Google Play build: same app + Google Play alternative-billing info screen and report token, built with --dart-define=PLAY_STORE=true). If Google billing isn't available, the Play build shows "Payments are not available in this version" and never opens UPI.

2. ADMIN PANEL + WEBSITE (Next.js, folder admin/, hosted on Vercel)
   - Panel pages: Dashboard, Channels (shows "Created by" email), Posts (premium 👑, trailers, scheduling, media upload), Approvals, Users (grant premium), Plans, Payments, Meta, Requests, Admins.
   - Payments: UPI ID / payee settings; orders with sort and filter by price, plan and date; sales per plan; repeat buyers with history; Google Play reporting card.
   - Meta: setup checklist, Pixel ID, Conversions API token, test event code, download page settings, APK upload, health check.
   - Requests: account deletion requests (with a "delete account" action), content / copyright reports, in-app reports.
   - Public website pages: "/" (shows /home to signed-out visitors, a Jollify-style landing page), /home, /d (ad download page with Meta Pixel), /api/click (stores the ad click), /privacy, /terms, /delete-account (whole account, or only some data), /report-content, plus a support form.

3. SUPABASE BACKEND (folder supabase/)
   - 16 migrations, 20261002000001 → 20261007000016. Tables: profiles, admins, installs, user_attribution, channels, channel_folders, channel_members, posts, post_items, admin_channel_access, plans, subscriptions, analytics_events, consents, reports, audit_logs, cloud_files, plan_requests, payment_settings, payment_orders, payment_events, meta_settings, meta_events, ad_clicks, play_settings, support_requests, plus storage buckets (including "downloads" for APKs) and Row Level Security on everything.
   - Cron jobs: cancel-stale-payment-orders (*/30), meta-dispatch (every minute, Meta Conversions API), play-report (*/5).
   - Extensions: pg_cron, pg_net.
   - Edge Functions: delete-account, play-report (verify_jwt = false). upi-webhook exists in the code but is NOT deployed.
   - Tests: supabase/tests/run_tests.sh.

4. META ADS TRACKING (ROAS-ready)
   - Ad → /d download page (Pixel PageView/ViewContent + click stored in ad_clicks with fbclid/fbc/fbp/IP/user agent) → the ads APK install is matched to the click by IP within 48 hours → app events (install, registration, purchase with plan and amount) are queued in meta_events → sent every minute by meta-dispatch to the Meta Conversions API with hashed email, external id, fbc, fbp, IP and user agent. action_source = website.

5. GITHUB ACTIONS (.github/workflows/mobile.yml)
   - On push / manual run: flutter analyze, flutter test, then build NEW_APP_NAME.apk (direct), NEW_APP_NAME-ads.apk (direct + APK_REFERRER utm_source=meta…) and NEW_APP_NAME-play.aab (play flavor), uploaded as one artifact "NEW_APP_NAME-apks".

======================================================================
STEP 1: COPY THE CODE EXACTLY
======================================================================
1. Clone the source repository at branch claude/pensive-allen-07t7y7.
2. Copy EVERY tracked file (admin/, mobile/, supabase/, docs/, .github/, README.md and every other file, including mobile/android/app/tester-release.jks) into NEW_GITHUB_REPO. Exclude only the .git folder.
3. Commit it as "Copy of source app" BEFORE changing anything, so every later change shows up in the diff.
4. Do NOT upgrade dependencies, do NOT run "flutter pub upgrade" or "npm update", do NOT change pubspec.lock or package-lock.json, do NOT reformat files.

======================================================================
STEP 2: WHAT TO CHANGE (ONLY THIS LIST)
======================================================================
A. App name: "Flixvault" → NEW_APP_NAME, in user-visible text only:
   - mobile/lib/config.dart → appName
   - every user-visible string in mobile/lib (policy screen, settings, share texts, report dialog, Play billing text)
   - mobile/test/payments_test.dart (expected name, so the test still passes)
   - mobile/android/app/src/main/AndroidManifest.xml → android:label
   - admin/: app/layout.tsx, (panel)/layout.tsx, login, home (website), d (download page), privacy, terms, delete-account, report-content, components/public-page.tsx, components/apk-upload.tsx, lib/policies.ts, (panel)/meta (page.tsx + actions.ts), (panel)/payments
   - supabase/migrations/20261005000013_meta_roas.sql → default page_title 'Flixvault'
   - .github/workflows/mobile.yml → the output file names and artifact name
   - README.md, docs/, supabase/README.md, mobile/README.md
   Check with: git grep -n -i "flixvault". It must return NOTHING at the end.
B. Support email: "info.flixvault@gmail.com" → NEW_SUPPORT_EMAIL everywhere (config.dart supportEmail, admin/lib/policies.ts SUPPORT_EMAIL, policies, docs).
C. Website: "https://flixvault-app.vercel.app" → NEW_WEBSITE_URL everywhere (config.dart website, policies, docs).
D. Supabase project:
   - mobile/lib/config.dart → supabaseUrl default "https://NEW_SUPABASE_REF.supabase.co", supabaseKey default NEW_SUPABASE_PUBLISHABLE_KEY
   - admin/.env.example, docs and READMEs: the old ref "lcfiohprmviolzwglkhm" → NEW_SUPABASE_REF
   Check: git grep -n "lcfiohprmviolzwglkhm" must return NOTHING.
E. Android package ids:
   - mobile/android/app/build.gradle.kts: defaultConfig applicationId "com.cloudstorage.app" → NEW_DIRECT_APP_ID; play flavor applicationId "com.flixvault.app" → NEW_PLAY_APP_ID
   - Login deep link: AndroidManifest.xml android:scheme="com.cloudstorage.app" AND mobile/lib/state/app_state.dart authRedirect 'com.cloudstorage.app://login-callback' → NEW_DIRECT_APP_ID (both must match exactly)
   - supabase/migrations/20261003000009_meta_pixel.sql → meta_settings default app_package 'com.cloudstorage.app' → NEW_DIRECT_APP_ID
   - supabase/migrations/20261006000014_play_alt_billing.sql → play_settings default package_name 'com.flixvault.app' → NEW_PLAY_APP_ID
   DO NOT CHANGE: namespace "com.cloudstorage.cloud_storage", the Kotlin folder paths (src/main, src/direct, src/play), the MainActivity / PlayBillingChannel package lines, or ANY MethodChannel name ('cloudstorage/upi', 'cloudstorage/playbilling', 'cloudstorage/screen', 'cloudstorage/download'…). They are internal; changing them breaks payments, downloads and rotation.
F. UPI: in supabase/migrations/20261003000007_upi_payments.sql change ONLY the seed line
   insert into public.payment_settings (upi_id, payee_name) values ('2728412a@bandhan', 'Cloud Storage');
   → values ('NEW_UPI_ID', 'NEW_UPI_PAYEE_NAME');
   Touch NOTHING else in the payment system: payments.dart, premium_screen.dart, the payment status screen, play_billing.dart, the UPI channel in MainActivity.kt, the payment SQL functions, cron, UTR checks, admin payments page.
G. Nothing else. No new features, no removed features, no renamed tables, functions, columns, buckets, routes or channels.

======================================================================
STEP 3: CHECK, THEN PUSH
======================================================================
1. cd mobile && flutter pub get && flutter analyze → "No issues found!"
2. cd mobile && flutter test → all pass
3. supabase/tests/run_tests.sh → all pass (needs PostgreSQL 15+; install it if missing)
4. cd admin && npm ci && npx tsc --noEmit && npx next build → succeeds
5. git grep -n -i -E "flixvault|lcfiohprmviolzwglkhm|2728412a@bandhan|com\.flixvault\.app" → nothing
   git grep -n "com.cloudstorage.app" → nothing (only the internal com.cloudstorage.cloud_storage namespace may remain)
6. git diff "Copy of source app" HEAD --stat → show me the list; it must contain only files from STEP 2.
Then push to NEW_GITHUB_REPO on the main branch.

======================================================================
STEP 4: NEW SUPABASE PROJECT (NEW_SUPABASE_REF, region Mumbai ap-south-1)
======================================================================
Use the Supabase MCP tools or the Supabase CLI ("supabase link --project-ref NEW_SUPABASE_REF" then "supabase db push").
1. Apply ALL 16 migrations in filename order, with only the STEP 2 edits. If one times out, split it into parts and run each part with "set lock_timeout = '10s';". Never skip one, never reorder.
2. Verify:
   select jobname, schedule from cron.job;  → cancel-stale-payment-orders, meta-dispatch, play-report
   select extname from pg_extension;        → includes pg_cron and pg_net
   select id from storage.buckets;          → all buckets from the migrations, including "downloads"
3. Deploy the Edge Functions from supabase/functions: delete-account (default settings) and play-report with verify_jwt = false. Do NOT deploy upi-webhook.
4. Set the plans exactly like my live app:
   update public.plans set duration_days = 7,   price_inr = 49  where code = 'trial';
   update public.plans set duration_days = 7,   price_inr = 129 where code = 'silver';
   update public.plans set duration_days = 30,  price_inr = 259 where code = 'gold';
   update public.plans set duration_days = 180, price_inr = 599 where code = 'platinum';
   update public.plans set duration_days = 365, price_inr = 999 where code = 'diamond';
5. Google Play reporting settings (keep enabled = false until Google approves billing):
   update public.play_settings set package_name = 'NEW_PLAY_APP_ID',
     function_url = 'https://NEW_SUPABASE_REF.supabase.co/functions/v1/play-report' where id = 1;
6. Check UPI: select upi_id, payee_name, enabled from public.payment_settings; → NEW_UPI_ID, NEW_UPI_PAYEE_NAME, enabled = true.
7. Authentication:
   - Email provider ON (email + password).
   - Authentication → URL Configuration → Redirect URLs: add NEW_DIRECT_APP_ID://login-callback, and Site URL = NEW_WEBSITE_URL.
   - Google provider: give me step-by-step instructions to create the Google OAuth client (redirect URI https://NEW_SUPABASE_REF.supabase.co/auth/v1/callback) and where to paste the Client ID and Secret in Supabase.
8. Owner account: create the auth user OWNER_EMAIL / OWNER_PASSWORD with the email already confirmed, then run:
   insert into public.admins (user_id, role) select id, 'owner' from auth.users where email = 'OWNER_EMAIL';
9. Run the Supabase security advisor and tell me about any warnings. Do NOT "fix" them by changing the copied functions without asking me.

======================================================================
STEP 5: GITHUB ACTIONS (APK / AAB)
======================================================================
1. Run the "Android app" workflow (workflow_dispatch) on main.
2. Confirm the artifact "NEW_APP_NAME-apks" contains NEW_APP_NAME.apk, NEW_APP_NAME-ads.apk and NEW_APP_NAME-play.aab.
3. Give me the run link. If it fails, fix only the cause (for example a name you missed in STEP 2) and run it again.

======================================================================
STEP 6: ADMIN PANEL + WEBSITE ON VERCEL
======================================================================
Give me step-by-step instructions to:
1. Vercel → Add New Project → import NEW_GITHUB_REPO → Root Directory = admin → Framework Next.js.
2. Environment variables (Production + Preview):
   NEXT_PUBLIC_SUPABASE_URL             = https://NEW_SUPABASE_REF.supabase.co
   NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY = NEW_SUPABASE_PUBLISHABLE_KEY
   SUPABASE_SECRET_KEY                  = NEW_SUPABASE_SECRET_KEY
3. Deploy and give me the URL. If it differs from NEW_WEBSITE_URL: update STEP 2C everywhere, push, rebuild the app (STEP 5) and update the Supabase Site URL.

======================================================================
STEP 7: META ADS TRACKING SETUP (same as the source)
======================================================================
Give me step-by-step instructions (I do these clicks myself) to:
1. Meta Events Manager → create a Pixel (data source "Web") → copy the Pixel ID.
2. Events Manager → Settings → Conversions API → Generate access token → copy it.
3. Admin panel → Meta page:
   - Pixel ID, Access token, Test event code (only while testing), Action source = Website, Website URL = NEW_WEBSITE_URL → Enable → Save
   - Download page: title NEW_APP_NAME, subtitle → upload NEW_APP_NAME-ads.apk with the APK upload box → Save
   - Every item in the setup checklist and health check must be green.
4. Meta Ads Manager: the ad destination URL = NEW_WEBSITE_URL/d; optimise for the Purchase event.
5. Test: open NEW_WEBSITE_URL/d?fbclid=test123 on a phone → download → install the ads APK → register → in Events Manager → Test events, PageView, ViewContent and the app events must appear. Check that admin Meta → health shows events being sent and no errors.

======================================================================
STEP 8: FINAL TEST (show me proof of each)
======================================================================
1. Admin: log in as OWNER_EMAIL → Payments shows UPI = NEW_UPI_ID; Plans show 49 / 129 / 259 / 599 / 999.
2. Website: "/", /home, /d, /privacy, /terms, /delete-account, /report-content all open and show NEW_APP_NAME and NEW_SUPPORT_EMAIL; the support / delete forms create rows in admin Requests.
3. App (NEW_APP_NAME.apk):
   - guest mode → email login → Google login
   - cloud upload, folders, delete
   - create a channel → admin approves it → upload a post with a trailer → Netflix-style page, Trailer, Play, rotation, More like this
   - Report and Block work, and the report shows in admin Requests
   - Premium screen shows the 5 plans → tap Trial → an order is created → the UPI app opens with pa = NEW_UPI_ID, pn = NEW_UPI_PAYEE_NAME, amount 49.00 → pay → the status screen updates (or the order shows as pending in admin Payments → Mark as paid with the UTR) → Premium is active → Download works
   - Settings → Delete account works
4. Write me a short report: the files changed (only the STEP 2 list) and a confirmation that nothing else changed.

RULES
- Never change the app flow or the payment flow.
- Never commit secret keys (only the publishable key goes in the app and repo).
- Never push to or open PRs on the source repository.
- If any step fails, tell me exactly what failed instead of working around it with a different design.
