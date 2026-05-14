# Auth0 Setup Guide — HopePMS

Step-by-step guide to wire Auth0 into HopePMS. Every value you collect here
plugs into a specific environment variable consumed by either the browser
(Astro/Vite — `PUBLIC_*`) or the Lambda layer ([server/lib/auth.ts](../server/lib/auth.ts)).

---

## 1. Mental model — what Auth0 pieces we use

This project uses **three Auth0 objects** and **two connections**:

| Auth0 object                                | Purpose                                                                 | Produces                                                        |
|---------------------------------------------|-------------------------------------------------------------------------|-----------------------------------------------------------------|
| **Tenant**                                  | Your isolated Auth0 environment (e.g. `hopepms-dev.us.auth0.com`).      | `PUBLIC_AUTH0_DOMAIN`                                           |
| **Application** (type: SPA)                 | Lets the Astro browser code start logins. Wraps the SPA SDK.            | `PUBLIC_AUTH0_CLIENT_ID`                                        |
| **API**                                     | The audience your access tokens are *for*. Lambdas validate this.       | `PUBLIC_AUTH0_AUDIENCE` + `AUTH0_AUDIENCE`                      |
| Connection: **Database**                    | Email/password sign-up — built in.                                      | —                                                               |
| Connection: **Google** (social)             | "Sign in with Google" buttons on the Login/Register pages.              | —                                                               |

> The HopePMS project uses Auth0 *only* for identity and access tokens. The
> 6 application rights (`PRD_ADD`, `PRD_EDIT`, …) live in Postgres in the
> `UserModule_Rights` table and are loaded by `GET /api/me` after every
> login. We do **not** use Auth0 RBAC for those rights.

---

## 2. Create the tenant

1. Go to [auth0.com](https://auth0.com) → **Sign up** (free tier covers up to ~7,500 active users — plenty for a capstone).
2. Pick a tenant name. Suggestion: `hopepms-dev` for development.
3. Region: pick the one closest to your RDS instance (US / EU / AU / JP).
4. After provisioning, your tenant domain is `hopepms-dev.us.auth0.com`
   (the region shortcode varies — `us`, `eu`, `au`, `jp`).

**Save this value** — it's `PUBLIC_AUTH0_DOMAIN` in `.env`.

> When you go to production, you should create a **second, separate tenant**
> (e.g. `hopepms-prod`). Never share dev and prod tenants — they have
> different user databases, different signing keys, and different security
> postures.

---

## 3. Create the Application (the SPA)

In the Auth0 dashboard:

1. **Applications → Applications → Create Application**
2. Name: `HopePMS Web`
3. Type: **Single Page Web Applications**
4. **Create**

After it's created, on its **Settings** tab fill in:

### Allowed Callback URLs

```
http://localhost:4321/auth/callback,
https://hopepms.example.com/auth/callback
```

This is where Auth0 redirects the browser *after* a successful login. The
URL must exactly match `PUBLIC_AUTH0_REDIRECT_URI`. The Astro route that
handles it is [src/pages/auth/callback.astro](../src/pages/auth/callback.astro).

### Allowed Logout URLs

```
http://localhost:4321/login,
https://hopepms.example.com/login
```

Where Auth0 sends the browser after `signOut()`. The call is in
[src/lib/auth/auth0Client.ts](../src/lib/auth/auth0Client.ts) (the `signOut`
function — `returnTo: window.location.origin + '/login'`).

### Allowed Web Origins

```
http://localhost:4321,
https://hopepms.example.com
```

Required so the SPA SDK can use silent-auth iframes and refresh-token rotation.

### Refresh Token Rotation (scroll further down)

- Toggle **Rotation** ON
- Toggle **Absolute Expiration** ON
- Set Absolute Lifetime to something reasonable (default 2592000s = 30 days).

This pairs with `useRefreshTokens: true` in
[src/lib/auth/auth0Client.ts](../src/lib/auth/auth0Client.ts). HopePMS keeps
refresh tokens **in JS memory only** (`cacheLocation: 'memory'`) so they never
touch disk.

### Save changes

Top of the Settings tab, copy:

- **Client ID** → `PUBLIC_AUTH0_CLIENT_ID` in `.env`

---

## 4. Create the API (the "audience")

The API object represents your backend; its identifier becomes the JWT
audience your Lambdas verify.

1. **Applications → APIs → Create API**
2. Name: `HopePMS API`
3. **Identifier**: `https://api.hopepms.example.com`
   > It does **not** have to be a real URL — Auth0 uses it as an opaque
   > string identifier. Use your real prod URL when you deploy, or any
   > stable URI you control.
4. Signing Algorithm: **RS256** (must match
   [server/lib/auth.ts:43](../server/lib/auth.ts#L43)).
5. **Create**.

On its **Settings** tab:
- **Enable RBAC**: ON
- **Add Permissions in the Access Token**: ON
- (We don't use Auth0 permissions for the 6 HopePMS rights, but having RBAC
  on means the tokens are clean and consistent.)

**Save**.

This identifier string becomes **both** `PUBLIC_AUTH0_AUDIENCE` (browser-side,
used when requesting tokens) and `AUTH0_AUDIENCE` (Lambda-side, used when
verifying them).

---

## 5. Enable Google sign-in (social connection)

The Login and Register pages both have a "Sign in with Google" button —
implemented in [src/pages/login.astro](../src/pages/login.astro) /
[src/pages/register.astro](../src/pages/register.astro) calling
`loginWithRedirect({ authorizationParams: { connection: 'google-oauth2' } })`.

For this to work end-to-end you need **your own Google OAuth credentials**.
By default Auth0 ships with shared dev credentials that work but are not
suitable for production.

### 5a. Create Google OAuth credentials

1. Go to [console.cloud.google.com](https://console.cloud.google.com).
2. Create a project (or pick an existing one). Project name doesn't matter.
3. **APIs & Services → OAuth consent screen**:
   - User Type: **External**
   - App name: `HopePMS`
   - User support email: your email
   - Developer contact: your email
   - **Save and continue** through scopes and test users (you can add scopes
     later; defaults are fine for sign-in only).
4. **APIs & Services → Credentials → Create Credentials → OAuth client ID**
5. Application type: **Web application**
6. **Authorized JavaScript origins** — your Auth0 tenant:
   ```
   https://hopepms-dev.us.auth0.com
   ```
7. **Authorized redirect URIs** — Auth0's callback (NOT your app's callback):
   ```
   https://hopepms-dev.us.auth0.com/login/callback
   ```
   > Swap in your actual tenant domain. The redirect goes to Auth0, then
   > Auth0 redirects to your app's `/auth/callback`.
8. **Create** → copy the **Client ID** and **Client Secret**.

### 5b. Configure Google in Auth0

1. In Auth0: **Authentication → Social → Google**.
2. Paste the **Client ID** and **Client Secret** from step 5a.7.
3. **Applications** tab inside that connection: enable for `HopePMS Web`.
4. **Save**.

> If you skip step 5 entirely, the "Sign in with Google" button will still
> work because Auth0 falls back to its dev credentials — but you'll see a
> "this connection uses Auth0's developer keys" warning at sign-in.

---

## 6. Fill in `.env`

Copy the example file and set the seven Auth0-related values:

```bash
cp .env.example .env
```

```bash
# ── Browser-visible (the PUBLIC_ prefix exposes them to client code via Vite) ──
PUBLIC_AUTH0_DOMAIN=hopepms-dev.us.auth0.com
PUBLIC_AUTH0_CLIENT_ID=ABCxyz123fromStep3
PUBLIC_AUTH0_AUDIENCE=https://api.hopepms.example.com
PUBLIC_AUTH0_REDIRECT_URI=http://localhost:4321/auth/callback

# ── Server-only (Lambda token verification) ──
AUTH0_ISSUER=https://hopepms-dev.us.auth0.com/         # ← trailing slash MATTERS
AUTH0_AUDIENCE=https://api.hopepms.example.com
AUTH0_JWKS_URI=https://hopepms-dev.us.auth0.com/.well-known/jwks.json
```

### ⚠️ Two common mistakes

| Mistake                                           | Symptom                                              | Fix                                              |
|---------------------------------------------------|------------------------------------------------------|--------------------------------------------------|
| Missing trailing slash on `AUTH0_ISSUER`          | Every `/api/me` returns 401 `bad_issuer`              | Add the `/` — Auth0 emits `iss` *with* slash     |
| `PUBLIC_AUTH0_AUDIENCE` ≠ `AUTH0_AUDIENCE`         | Every token rejected as `bad_audience`                | Use the **exact same string** in both vars       |

---

## 7. Seed the SUPERADMIN

The SUPERADMIN row in Postgres needs the Auth0 user's `sub` claim (e.g.
`auth0|6543abcdef…` for email signups, `google-oauth2|10987…` for Google).
You don't know that until the user exists in Auth0.

There are two routes. The **recommended** one uses HopePMS's just-in-time
provisioning to discover the sub for you.

### Recommended: let JIT provisioning create the row, then promote

1. Start the dev server:
   ```bash
   npm run dev
   ```
2. Open <http://localhost:4321/register>.
3. Sign up the SUPERADMIN account (the docx names `jcesperanza@neu.edu.ph`).
   Use either email/password or Google.
4. After email confirmation, the browser redirects through `/auth/callback`.
   You'll then land on `/login?reason=not_activated`. **That's expected** —
   the JIT trigger in [server/handlers/me.ts](../server/handlers/me.ts) just
   created the row as `USER` / `INACTIVE`.
5. Find the Auth0 sub:
   ```bash
   psql "$DATABASE_URL" -c 'SELECT "userId", username, email FROM hopedb."user";'
   ```
   You'll see something like `auth0|6543abc123…` or `google-oauth2|11211…`.
6. Promote the row to SUPERADMIN and flip all rights:
   ```sql
   SET search_path = hopedb, public;

   UPDATE "user"
     SET user_type     = 'SUPERADMIN',
         record_status = 'ACTIVE',
         stamp         = 'SEEDED system ' || to_char(NOW(), 'YYYY-MM-DD HH24:MI')
     WHERE email = 'jcesperanza@neu.edu.ph';

   -- Turn on every module
   UPDATE user_module
     SET rights_value = 1
     WHERE userid = (SELECT "userId" FROM "user" WHERE email = 'jcesperanza@neu.edu.ph');

   -- Turn on every right
   UPDATE "UserModule_Rights"
     SET "Right_value" = 1
     WHERE userid = (SELECT "userId" FROM "user" WHERE email = 'jcesperanza@neu.edu.ph');
   ```
7. Sign in again at <http://localhost:4321/login>. You're now SUPERADMIN with
   the full sidebar (Products, Deleted Items, both Reports, Manage Users).

> The `trg_protect_superadmin` trigger in
> [server/db/schema.sql](../server/db/schema.sql) blocks UPDATEs on SUPERADMIN
> rows from non-SUPERADMIN callers. It checks the `hopepms.caller_userid`
> session GUC — which Lambda handlers set before any UPDATE. Direct `psql`
> connections (like the one you just used) don't set that GUC, but the
> trigger only fires on UPDATE *to a row whose OLD.user_type was already
> SUPERADMIN*. Since you're promoting FROM `USER` *to* `SUPERADMIN`, the
> trigger doesn't trip. After promotion, the row is protected.

### Alternative: pre-create the user in Auth0, then run the seed file

If you'd rather have [server/db/seed-superadmin.sql](../server/db/seed-superadmin.sql)
work on a fresh DB without manual SQL surgery:

1. In Auth0 dashboard: **User Management → Users → Create User**
   - Email: `jcesperanza@neu.edu.ph`
   - Password: pick one
   - Connection: `Username-Password-Authentication`
2. After creation, copy the user's **user_id** (top of their detail page —
   looks like `auth0|6543abcdef…`).
3. In `server/db/seed-superadmin.sql`, replace all 5 occurrences of
   `auth0|REPLACE_ME` with that exact string.
4. Run the seed:
   ```bash
   psql "$DATABASE_URL" -f server/db/seed-superadmin.sql
   ```

---

## 8. Test the round-trip

1. `npm run dev` (Astro + Lambda proxy on port 4321).
2. Hit <http://localhost:4321/login>.
3. Sign in as the SUPERADMIN.

**Expected flow:**

```
/login
  ↓  click "Continue" or "Sign in with Google"
auth0.com  (Universal Login screen)
  ↓
/auth/callback   ← "Signing you in…" spinner for ~1s
  ↓  hits GET /api/me, gets your user_type + rights
/products        ← full sidebar appears, idle-timer chip in top bar
```

### Debugging failed logins

Open DevTools → Network and look at the `/api/me` call:

| Response                    | Meaning                                                | Fix                                                              |
|-----------------------------|---------------------------------------------------------|------------------------------------------------------------------|
| `401 unauthenticated`        | Token verification failed in `server/lib/auth.ts`       | Check `AUTH0_ISSUER` trailing slash; re-check `AUTH0_AUDIENCE`   |
| `403 not_activated`          | Row exists in `hopedb."user"` but `record_status='INACTIVE'` | Promote it (step 7.6) or wait for ADMIN activation              |
| `500 provision_failed`       | Postgres connection or trigger error                    | Check `PG_*` env vars, RDS security group, SSL setting           |
| Browser console: `bad_issuer` | `iss` mismatch                                          | Trailing slash on `AUTH0_ISSUER`                                  |
| Browser console: `bad_audience` | `aud` mismatch                                       | `PUBLIC_AUTH0_AUDIENCE` and `AUTH0_AUDIENCE` must be identical    |
| Auth0 redirect fails: "Callback URL mismatch" | Browser tried to redirect to an unlisted URL | Add the URL to Application → Allowed Callback URLs              |

To see the raw access token's claims (debug only — never log this in prod):

```js
// In DevTools console, after login:
const { readSession } = await import('/src/lib/security/session.ts');
const s = await readSession();
console.log(JSON.parse(atob(s.accessToken.split('.')[1])));
```

You should see `iss`, `aud`, `sub`, `exp`. They should match your env vars.

---

## 9. Going to production

When you deploy to AWS Amplify + API Gateway:

1. **Create a second Auth0 tenant** (`hopepms-prod`) — repeat steps 2–5
   with production URLs:
   - Callback: `https://hopepms.<your-domain>.com/auth/callback`
   - Logout: `https://hopepms.<your-domain>.com/login`
   - Google OAuth redirect URI: `https://hopepms-prod.us.auth0.com/login/callback`
2. **Set the prod env vars** in:
   - Amplify console (for the Astro frontend's `PUBLIC_*` vars at build time)
   - Lambda environment (for the server-side `AUTH0_*` vars at runtime)
3. **Rotate the session pepper** — generate a fresh `PUBLIC_SESSION_PEPPER`
   per environment:
   ```bash
   node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"
   ```
4. **Lock down CORS** — set `CORS_ALLOWED_ORIGIN` in Lambda env to your
   production frontend origin only (no `localhost`).

---

## Reference — files that consume Auth0 config

| Env var                      | Read by                                                                                              |
|------------------------------|------------------------------------------------------------------------------------------------------|
| `PUBLIC_AUTH0_DOMAIN`         | [src/lib/auth/auth0Client.ts](../src/lib/auth/auth0Client.ts)                                       |
| `PUBLIC_AUTH0_CLIENT_ID`      | [src/lib/auth/auth0Client.ts](../src/lib/auth/auth0Client.ts)                                       |
| `PUBLIC_AUTH0_AUDIENCE`       | [src/lib/auth/auth0Client.ts](../src/lib/auth/auth0Client.ts)                                       |
| `PUBLIC_AUTH0_REDIRECT_URI`   | [src/lib/auth/auth0Client.ts](../src/lib/auth/auth0Client.ts)                                       |
| `AUTH0_ISSUER`                | [server/lib/auth.ts](../server/lib/auth.ts) (iss check)                                              |
| `AUTH0_AUDIENCE`              | [server/lib/auth.ts](../server/lib/auth.ts) (aud check)                                              |
| `AUTH0_JWKS_URI`              | [server/lib/auth.ts](../server/lib/auth.ts) (RS256 public key fetch + cache)                         |

If you change a `PUBLIC_*` value, **restart `npm run dev`** — Vite bakes those
into the client bundle at build/dev-start time, not on the fly.
