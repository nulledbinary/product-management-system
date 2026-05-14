# HopePMS — Astro & AWS Cloud-Native Edition

Hope, Inc. Product Management System (HopePMS). A secure, role-aware product
management web app for the HopeDB schema.

> Stack: **Astro 4 (SSR · Node adapter) · Tailwind CSS · Auth0 SPA · AWS Lambda · API Gateway · RDS PostgreSQL**

---

## Why this is structured this way

This codebase implements [PROJECT_PLAN.md](./PROJECT_PLAN.md) — Astro + AWS
edition. The business rules (no-hard-delete, soft-delete visibility,
SUPERADMIN protection, audit stamps, rights matrix) come from the original
HopePMS guide. Everything is enforced **in two places**: the UI for UX, and
the Lambda+SQL layer for security.

### Project layout

```
.
├── astro.config.mjs            # Astro SSR config (Node adapter)
├── tailwind.config.mjs         # Design tokens — gradient mesh, glassmorphism
├── tsconfig.json               # @/, @components/, @lib/, @server/ path aliases
├── .env.example                # Copy to .env and fill in
├── public/                     # Static assets (favicon, Google icon)
└── src/
    ├── layouts/
    │   ├── BaseLayout.astro    # <html> shell, hero gradient mesh background
    │   └── AppShell.astro      # Authenticated shell — guards, sidebar, top bar, timer
    ├── components/
    │   ├── Sidebar.astro       # Rights-gated nav
    │   ├── TopBar.astro        # Title + idle timer chip
    │   ├── ProductFormModal.astro
    │   ├── ConfirmModal.astro
    │   └── ui/Logo.astro
    ├── pages/
    │   ├── index.astro         # → /products
    │   ├── login.astro         # Email + Google OAuth (Auth0 Universal Login)
    │   ├── register.astro
    │   ├── auth/callback.astro
    │   ├── products/
    │   │   ├── index.astro     # Active list + CRUD + price history drawer
    │   │   └── deleted.astro   # Admin-only recovery panel
    │   ├── reports/
    │   │   ├── product-listing.astro
    │   │   └── top-selling.astro
    │   ├── admin/users.astro   # SUPERADMIN-protected user management
    │   └── api/[...route].ts   # Dev-time proxy → Lambda handlers
    ├── styles/global.css       # Tailwind layers + components (.btn-*, .field, .glass, …)
    └── lib/
        ├── security/           # ⚠️ critical — see “Security model” below
        │   ├── crypto.ts       # AES-GCM, HKDF, per-tab ephemeral salt
        │   ├── session.ts      # Encrypted sessionStorage vault
        │   ├── timeout.ts      # Inactivity watchdog
        │   ├── pathGuard.ts    # ../../../ blocklist + structured whitelists
        │   ├── sanitize.ts     # HTML/URL output encoding
        │   └── index.ts
        ├── auth/
        │   ├── auth0Client.ts  # SPA client (cacheLocation: 'memory')
        │   └── rights.ts       # useRights / isAdmin / canSeeStamp
        ├── api/
        │   ├── client.ts       # Authenticated fetch wrapper
        │   ├── products.ts
        │   └── users.ts
        └── utils/stamp.ts      # 'ACTION userId YYYY-MM-DD HH:MM'

server/                         # ← Deployed as AWS Lambda
├── tsconfig.json
├── lib/
│   ├── auth.ts                 # Auth0 JWT verifier (RS256, JWKS cache)
│   ├── http.ts                 # API Gateway response helpers + CORS lock
│   ├── sanitize.ts             # Server mirror of pathGuard
│   ├── stamp.ts
│   └── rights.ts
├── db/
│   ├── pool.ts                 # pg pool, parameterised query helper, withTx
│   ├── schema.sql              # Full HopeDB DDL + HopePMS additions + triggers + views
│   ├── seed-hopedb.sql         # Original HopeDB business data (HopeDB (3).sql)
│   └── seed-superadmin.sql     # SUPERADMIN seed (auth0|REPLACE_ME)
└── handlers/
    ├── me.ts                   # GET /api/me — JIT provisioning + 403 not_activated
    ├── products.ts             # CRUD + soft delete + price history
    ├── admin-users.ts          # Activate / deactivate (SUPERADMIN-protected)
    └── reports.ts              # REP_001 / REP_002
```

---

## 🔒 Security model

### 1. Encrypted, ephemeral session storage

- We use **sessionStorage** — auto-cleared by the browser when the tab closes.
- Every value is wrapped with **AES-256-GCM**. The key is derived via HKDF
  from `PUBLIC_SESSION_PEPPER` (build-time) + a **per-tab ephemeral salt**
  that lives only in JS memory ([src/lib/security/crypto.ts](src/lib/security/crypto.ts)).
- Refresh tokens never touch disk: Auth0 SPA SDK is configured with
  `cacheLocation: 'memory'` ([src/lib/auth/auth0Client.ts](src/lib/auth/auth0Client.ts:30)).
- The vault is wiped on `beforeunload`, `pagehide`, sign-out, and inactivity
  timeout. The watchdog is in [src/lib/security/timeout.ts](src/lib/security/timeout.ts).

### 2. Path-traversal prevention (`../../../`)

- A shared blocklist rejects `..`, URL-encoded `%2e%2e`, double-encoded
  `%252e%252e`, `..`, null bytes, and other C0 controls
  ([src/lib/security/pathGuard.ts](src/lib/security/pathGuard.ts)).
- Structured fields (`prodCode`, `username`, `email`, …) go through strict
  **whitelist regexes** before they touch the network.
- The exact same checks live in
  [server/lib/sanitize.ts](server/lib/sanitize.ts) — the Lambda layer never
  trusts the browser's validation. Every handler runs `assertSafeString` on
  the request path before pattern-matching it.
- The API client refuses to build a URL whose path contains traversal
  ([src/lib/api/client.ts:18](src/lib/api/client.ts#L18)).

### 3. No DELETE anywhere

- All removals set `record_status = 'INACTIVE'` via PATCH.
- A Postgres trigger (`trg_no_delete_*`) **raises** on any DELETE attempt
  ([server/db/schema.sql](server/db/schema.sql)).

### 4. SUPERADMIN protection

Enforced at three layers:

1. UI buttons are disabled with a tooltip on SUPERADMIN rows
   ([src/pages/admin/users.astro](src/pages/admin/users.astro)).
2. The Lambda handler 403s if the target is SUPERADMIN and the caller is not
   ([server/handlers/admin-users.ts:53](server/handlers/admin-users.ts#L53)).
3. The Postgres trigger `enforce_superadmin_protection` re-checks using
   the `hopepms.caller_userid` GUC the handler sets per transaction.

### 5. Audit stamps

Format `ACTION userId YYYY-MM-DD HH:MM`. Generated by the handler, never the
client. Hidden from USER accounts in both API responses and UI.

---

## Getting started

```bash
# 1. Install
npm install

# 2. Configure
cp .env.example .env
#    Fill in Auth0, RDS, and session pepper values.
#    Auth0 setup walkthrough: docs/AUTH0_SETUP.md
#    Generate the pepper:
node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"

# 3. Provision the database — load in this order:
psql "$DATABASE_URL" -f server/db/schema.sql           # schema + triggers + views + rights catalog
psql "$DATABASE_URL" -f server/db/seed-hopedb.sql      # original HopeDB business data (HopeDB (3).sql)
psql "$DATABASE_URL" -f server/db/seed-superadmin.sql  # SUPERADMIN row (edit 'auth0|REPLACE_ME' first)

# 4. Run dev server (Astro + Lambda proxy on :4321)
npm run dev
```

> The HopeDB seed file mirrors `HopeDB (3).sql` from the instructor's reference,
> with the `record_status` / `stamp` columns added by HopePMS populated to
> sensible defaults (`ACTIVE` / `IMPORTED system <date>`). It includes
> employees, departments, jobs, customers, sales, salesDetail, products,
> payments, and priceHist.

`npm run dev` mounts the Lambda handlers behind `/api/*` via
[src/pages/api/[...route].ts](src/pages/api/%5B...route%5D.ts) so the same
handler code that ships to AWS runs locally.

For production:

- Front-end → AWS Amplify (Astro Node adapter)
- API → API Gateway HTTP API → individual Lambdas per route group
  (`me`, `products`, `admin-users`, `reports`)
- DB → RDS PostgreSQL with SSL required

---

## Routes & rights

| Page                            | Right needed | Notes                                                |
|---------------------------------|--------------|------------------------------------------------------|
| `/products`                      | (any active) | USER sees ACTIVE only · stamp hidden                 |
| `/products` Add button           | `PRD_ADD`    |                                                      |
| `/products` Edit button          | `PRD_EDIT`   |                                                      |
| `/products` Delete button        | `PRD_DEL`    | Soft delete only                                     |
| `/products/deleted`              | ADMIN+       | Recovery panel                                        |
| `/reports/product-listing`       | `REP_001`    |                                                      |
| `/reports/top-selling`           | `REP_002`    | SUPERADMIN only per the rights matrix                |
| `/admin/users`                   | `ADM_USER`   | SUPERADMIN rows always read-only for ADMIN callers   |

---

## Definition of Done coverage (PROJECT_PLAN.md §✅)

| Mandate                                       | Where it lives                                                  |
|-----------------------------------------------|-----------------------------------------------------------------|
| No DELETE statements                          | Postgres trigger `reject_hard_delete` + no DELETE in source     |
| Soft-deleted invisible to USER                | Lambda filter + UI filter (`/api/products` list)                 |
| SUPERADMIN protection (UI + API)              | Modal-disabled button + handler check + DB trigger              |
| Audit stamps on every write                   | `makeStamp()` called in every handler write path                |
| Stamp hidden from USER                        | `sanitiseRow` in products handler + `canSeeStamp` in UI          |
| Live production URL                           | Deploy via Amplify (see Getting started)                        |

---

**Prepared by:** Boris Gamaliel D. Duque & Yna Solitario · **Instructor:** Jeremias C. Esperanza
**Institution:** New Era University – College of Computer Studies · **Course:** Software Engineering 2
