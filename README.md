# HopePMS — Astro & AWS Cloud-Native Edition

Hope, Inc. Product Management System (HopePMS). A secure, role-aware product
management web app for the HopeDB schema.

> Stack: **Astro 4 (static, prerendered) · Tailwind CSS · Auth0 (server-side
> Authorization Code + PKCE) · Spring Boot 3.4 / Java 21 · Amazon ECS Fargate ·
> ElastiCache Redis · RDS PostgreSQL 16 · Terraform**

---

## Why this is structured this way

This codebase implements [PROJECT_PLAN.md](./PROJECT_PLAN.md). The business
rules (no-hard-delete, soft-delete visibility, SUPERADMIN protection, audit
stamps, rights matrix) come from the original HopePMS guide. Everything is
enforced **in two places**: the UI for UX, and the Spring Boot + SQL layer for
security — the browser is never trusted.

The frontend is a **static** Astro site (no SSR, no Node server). All dynamic
concerns — auth, sessions, rights, data — live in the Spring Boot backend. The
browser reaches it through an Amplify `/api/*` rewrite.

> **Heads-up on `server/`:** the `server/` directory is a *legacy* prototype of
> an earlier Node/Lambda design and is **not deployed or used**. The live
> backend is `backend/` (Spring Boot on ECS). `server/` is retained for
> reference only; ignore it when reasoning about runtime behaviour.

### Repository layout

```
.
├── astro.config.mjs            # output: 'static' — prerendered; dev proxies /api → :8080
├── tailwind.config.mjs         # Monochrome design tokens, gradient mesh
├── amplify.yml                 # Amplify static build + security headers (CSP/HSTS)
├── src/                        # ← Astro static frontend (deployed to AWS Amplify)
│   ├── layouts/
│   │   ├── BaseLayout.astro    # <html> shell, hero gradient mesh background
│   │   └── AppShell.astro      # Auth boot guard (+retry), sidebar, top bar,
│   │   │                       #   idle timer, live identity watch
│   ├── components/             # Sidebar, TopBar, ProductFormModal, ConfirmModal, ui/Logo
│   ├── pages/
│   │   ├── index.astro         # → /dashboard
│   │   ├── login.astro         # Email + Google; client-side ?reason= banner/modal
│   │   ├── register.astro      # Kicks off Auth0 sign-up
│   │   ├── dashboard.astro     # Default landing — catalogue + activity overview
│   │   ├── products/{index,deleted}.astro
│   │   ├── reports/{product-listing,top-selling}.astro
│   │   └── admin/{users,logs,superadmins}.astro
│   ├── styles/global.css       # Tailwind layers + components (.btn-*, .field, .grad-*, …)
│   └── lib/
│       ├── auth/
│       │   ├── volatileSession.ts  # ⚠️ session model — see “Security model”
│       │   └── rights.ts           # useRights / isAdmin / canSeeStamp
│       ├── api/                    # client.ts (credentialed fetch), products.ts, users.ts
│       ├── security/               # pathGuard.ts, sanitize.ts, timeout.ts, index.ts
│       └── utils/                  # stamp.ts, liveRefresh.ts, animateNumber.ts
│
├── backend/                    # ← Spring Boot 3.4 API (deployed to Amazon ECS)
│   └── … see backend/README.md
└── infra/                      # ← Terraform for every AWS resource
    └── … see infra/README.md
```

Backend internals (Auth0 flow, sessions, rights aspect, Flyway migrations) are
documented in **[backend/README.md](./backend/README.md)**. The full AWS
topology, cost, and apply walkthrough are in
**[infra/README.md](./infra/README.md)**. This file covers the system model and
local setup.

---

## 🔒 Security model

### 1. Volatile sessions — zero client-side state

There is **no** localStorage, sessionStorage, IndexedDB, or client-managed
cookie holding any session state. Instead:

- The only thing persisted in the browser is an **opaque session id** in an
  `HttpOnly; Secure; SameSite=Strict` cookie named `HPMS_SID`, minted by the
  backend. It has **no `Max-Age`/`Expires`**, so the browser drops it the
  moment the tab closes.
- The server-side session lives in **ElastiCache Redis** with a sliding TTL:
  **30 min** standard, **8 min** for SUPERADMIN (tighter privileged window).
- Identity/rights are cached in the browser only in a Nano Stores `atom`
  ([src/lib/auth/volatileSession.ts](src/lib/auth/volatileSession.ts)) — plain
  JS heap, gone on refresh. `beforeunload`/`pagehide`/tab-hidden best-effort
  call `/api/auth/invalidate` so the Redis entry is dropped immediately.
- Tokens never reach the browser: Auth0 runs as a **Regular Web App**
  (Authorization Code + PKCE) entirely server-side
  ([AuthController.java](backend/src/main/java/com/hopepms/auth/AuthController.java)).
  The SPA SDK is never loaded.

### 2. Real-time access resolution (live rights/role)

Role, rights, and account status are resolved **live, per request** — never
frozen into the session at login.

- [VolatileSessionFilter.java](backend/src/main/java/com/hopepms/security/VolatileSessionFilter.java)
  rebuilds the request principal every request from
  `UserAccessService.snapshot(userId)`: Redis `hpms:acl:{userId}` (120 s
  safety-net TTL) → on miss, the database via `ProvisioningService.loadOrNull`
  (the *single* rights query — same SQL as login, so parity is guaranteed).
- A `@TransactionalEventListener(AFTER_COMMIT)`
  ([UserAccessChangedListener.java](backend/src/main/java/com/hopepms/security/UserAccessChangedListener.java))
  evicts the ACL cache key the instant a promote / demote / activate /
  deactivate / right-grant commits, so the change takes effect on that user's
  **very next request** — no re-login, no waiting for a TTL.
- The login-time `SessionRecord` is **no longer** the authorization source of
  truth; the filter ignores its rights/userType except as the fail-open
  fallback below.

### 3. Session-lifetime contract

> **A live session ends only on explicit logout or the inactivity/TTL timeout.
> Nothing the request itself observes destroys a session.**

The request hot path is deliberately non-destructive. Depending on what
`snapshot()` resolves to, the filter:

- **active** → rebuilds the principal from it; the new role/rights apply
  immediately and the TTL slides.
- **null** (DB threw / indeterminate) → **fails open**: rebuilds from the
  login-time snapshot and slides the TTL. A rights change is merely delayed,
  never a logout.
- **not-active** (deactivated / row gone) → **withholds all authority** for
  the request (protected endpoints 401), but does **not** invalidate the
  session or clear the cookie.

So a genuine deactivation stops working at once (zero rights) and the user is
routed out by the normal SPA flow, while a *spurious / transient* not-active
self-heals on the next request — never a permanent, abrupt logout.

The client mirrors this fail-open stance: `fetchIdentity()` returns a 3-state
probe (`ok | unauthenticated | unavailable`); **only HTTP 401 is a logout**.
5xx / network / timeout are transient — the [AppShell](src/layouts/AppShell.astro)
boot guard retries with backoff instead of redirecting on a single blip, and
`startIdentityWatch` never counts transient failures toward its 2-strike rule.
`startIdentityWatch` also reconciles a live promote/demote in place (re-gating
the chrome without a reload).

### 4. Activation gate

A brand-new Auth0/Google sign-up is provisioned `USER` / `INACTIVE`. The
backend bounces it to `/login?reason=activation_required`. Because the site is
statically built, [login.astro](src/pages/login.astro) resolves `?reason=`
**client-side** (off `window.location.search`) and shows a blocking
“Wait for an Administrator to activate your account.” modal. An ADMIN/SUPERADMIN
must activate the account via the Admin module before sign-in succeeds.

### 5. Rights enforcement & SUPERADMIN protection

- Backend: `@RequiresRight("PRD_DEL")` etc., evaluated by
  [RequiresRightAspect.java](backend/src/main/java/com/hopepms/security/RequiresRightAspect.java)
  against the live principal.
- SUPERADMIN rows are protected at three layers: disabled UI controls →
  handler 403 → the Postgres `enforce_superadmin_protection` trigger
  (re-checks via the `hopepms.caller_userid` GUC set per transaction).

### 6. No DELETE, ever

- All removals set `record_status = 'INACTIVE'`; the `reject_hard_delete()`
  Postgres trigger **raises** on any DELETE attempt — defence in depth even if
  application logic is bypassed.

### 7. Path-traversal & input hardening

- A shared blocklist rejects `..`, `%2e%2e`, double-encoded `%252e%252e`, null
  bytes, and C0 controls
  ([src/lib/security/pathGuard.ts](src/lib/security/pathGuard.ts)); structured
  fields go through strict whitelist regexes before hitting the network. The
  backend re-validates server-side and never trusts the browser.

### 8. Audit stamps

Format `ACTION userId YYYY-MM-DD HH:MM`. Generated server-side, never by the
client, and hidden from USER accounts in both API responses and the UI.

---

## Getting started (local development)

You need two processes: the Spring Boot backend (`:8080`) and the Astro dev
server (`:4321`, which proxies `/api/*` to the backend).

### 1. Backend

Full instructions — Postgres + Redis, env vars, Flyway, Auth0 app setup — are
in **[backend/README.md](./backend/README.md)**. In short:

```bash
docker run -d --name pg    -p 5432:5432 -e POSTGRES_DB=hopedb \
  -e POSTGRES_USER=hopepms -e POSTGRES_PASSWORD=hopepms postgres:16
docker run -d --name redis -p 6379:6379 redis:7-alpine

cd backend
export AUTH0_DOMAIN=... AUTH0_CLIENT_ID=... AUTH0_CLIENT_SECRET=...
export SESSION_COOKIE_SECURE=false        # local HTTP only
sh ./mvnw spring-boot:run                  # mvnw is the POSIX script (no mvnw.cmd)
```

Flyway auto-runs the migrations on first boot — it creates the `hopedb`
schema, seeds the modules/rights catalog, and inserts the SUPERADMIN
placeholder row. Configuration is env-driven; defaults are in
[backend/src/main/resources/application.yml](backend/src/main/resources/application.yml).

### 2. Frontend

```bash
npm install
npm run dev          # Astro on http://localhost:4321, /api/* proxied to :8080
```

The only frontend env var still in use is `PUBLIC_SESSION_TIMEOUT_MIN`
(inactivity-timer display, default 30). The root `.env.example` predates the
current architecture — the Auth0-SPA / `PUBLIC_SESSION_PEPPER` / Lambda
entries in it are **dead** and can be ignored; backend config lives in
`application.yml` and the ECS task definition, not a frontend `.env`.

### Production

- **Frontend** → AWS Amplify (static `dist/`, security headers via
  [amplify.yml](./amplify.yml)).
- **Backend** → Amazon ECS Fargate, reached as `/api/*` via Amplify rewrite →
  CloudFront → ALB → ECS. CI deploys on push to `master` touching `backend/**`.
- **Data** → RDS PostgreSQL 16 (Multi-AZ) + ElastiCache Redis 7.
- Full topology, cost, and a step-by-step apply: **[infra/README.md](./infra/README.md)**.

---

## Routes & rights

| Page                          | Access needed | Notes                                              |
|-------------------------------|---------------|----------------------------------------------------|
| `/`                           | —             | Redirects to `/dashboard`                          |
| `/dashboard`                  | any active    | Default landing — catalogue + activity overview    |
| `/products`                   | any active    | USER sees ACTIVE only · stamp hidden               |
| `/products/deleted`           | ADMIN+        | Soft-delete recovery panel                         |
| `/reports/product-listing`    | `REP_001`     |                                                    |
| `/reports/top-selling`        | `REP_002`     | SUPERADMIN only per the rights matrix              |
| `/admin/users`                | `ADM_USER`    | SUPERADMIN rows read-only for ADMIN callers        |
| `/admin/logs`                 | `ADM_USER`    | Audit / activity log                               |
| `/admin/superadmins`          | owner only    | SUPERADMIN control (system owner)                  |

Within `/products`, the action buttons each need their own right: **Add**
requires `PRD_ADD`, **Edit** requires `PRD_EDIT`, **Delete** requires
`PRD_DEL` (soft-delete only).

All page guards are enforced again server-side per request; UI gating is UX
only.

---

## Definition of Done coverage (PROJECT_PLAN.md §✅)

| Mandate                          | Where it lives                                                        |
|----------------------------------|-----------------------------------------------------------------------|
| No DELETE statements             | Postgres `reject_hard_delete` trigger + no DELETE in source           |
| Soft-deleted invisible to USER   | Backend query filter + UI filter                                      |
| SUPERADMIN protection (UI+API+DB)| Disabled controls + handler 403 + `enforce_superadmin_protection`     |
| Audit stamps on every write      | `StampHelper` invoked in every backend write path                     |
| Stamp hidden from USER           | Backend response sanitisation + `canSeeStamp` in UI                   |
| Live rights/role changes         | `UserAccessService` + `VolatileSessionFilter` (resolved per request)  |
| Session only ends on logout/idle | Non-destructive request hot path (see Security model §3)              |
| Live production URL              | AWS Amplify + ECS (see infra/README.md)                               |

---

**Prepared by:** Boris Gamaliel D. Duque & Yna Solitario · **Instructor:** Jeremias C. Esperanza
**Institution:** New Era University – College of Computer Studies · **Course:** Software Engineering 2
