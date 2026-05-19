# HopePMS — Astro & AWS Cloud-Native Edition

Hope, Inc. Product Management System (HopePMS). A secure, role-aware product
management web app for the HopeDB schema.

> Stack: **Astro 4 (SSR · Node adapter) · Tailwind CSS · Auth0 SPA · AWS Amplify · API Gateway · RDS PostgreSQL · SpringBoot (Authentication and SSO Security)**

---

## Why this is structured this way

This codebase implements [PROJECT_PLAN.md](./PROJECT_PLAN.md) — Astro + AWS
edition. The business rules (no-hard-delete, soft-delete visibility,
SUPERADMIN protection, audit stamps, rights matrix) come from the original
HopePMS guide. Everything is enforced **in two places**: the UI for UX, and
the Lambda+SQL layer for security.

### Project layout

Here is a plain-English breakdown of your project. Instead of looking at it purely as a list of files, I’ve organized it into the functional "building blocks" of how your application works, from what the user sees down to the database.

### 1. The Setup & Foundation (Root Files)

This is the instruction manual for the app's environment. It tells the system how to build the app, how to style it, and where to find key configurations.

* **The Framework:** Powered by Astro for rendering pages.
* **The Look:** Styled with Tailwind CSS, utilizing modern design elements like glassmorphism (frosted glass effects) and gradient meshes.
* **Environment:** Secure placeholders for API keys and environment variables.

### 2. The Frontend: What the User Sees (`/src`)

This is the entire visual side of the app that runs in the user's web browser.

* **The Layouts:**
* The core foundation of every page (the `<html>` shell and background).
* The "Secure Shell" that wraps the app once a user logs in, giving them a sidebar, a top navigation bar, and an idle timer.


* **The Pages (Screens):**
* **Authentication:** Login and registration pages, powered by Auth0 (Google OAuth + Email).
* **Products:** A dashboard to view, add, edit, and delete products, including a drawer to see price history. It also has a special "recycle bin" where admins can recover deleted items.
* **Reports:** Pages dedicated to generating business reports (like product listings and top sellers).
* **Admin Tools:** A restricted area where "Superadmins" can manage users.


* **The Components:** Reusable UI pieces like logos, confirmation pop-ups, product forms, and the navigation menus.

### 3. Frontend Security & Logic (`/src/lib`)

This is the "bodyguard" that lives in the user's browser, making sure they behave and stay safe.

* **Critical Security:**
* Encrypts the user's session data in the browser so it can't be stolen.
* Watches for user inactivity to automatically log them out.
* Sanitizes everything to prevent malicious code from being injected.
* Blocks users from accessing folders or paths they shouldn't see.


* **Permissions:** Checks what rights a user has (e.g., "Is this person an admin?" or "Are they allowed to see this action stamp?").
* **API Client:** A secure messenger that takes the user's requests and sends them to the backend server.

### 4. The Backend: The Engine Room (`/server`)

This is the hidden server-side code deployed on AWS (as Lambda functions). It processes requests, handles the heavy lifting, and enforces ultimate security.

* **Security Checkpoint (`lib/auth.ts`):** Before the server does *anything*, it verifies the user's identity (validating their JWT token from Auth0).
* **The Handlers (API Endpoints):**
* **User Provisioning (`me.ts`):** Creates user profiles on the fly when they first log in, but blocks them if their account hasn't been activated by an admin.
* **Products (`products.ts`):** Actually executes the creation, reading, updating, and "soft deleting" of products, while logging price histories.
* **Admin Tools (`admin-users.ts`):** The secure endpoint that allows Superadmins to activate or deactivate staff accounts.
* **Reports (`reports.ts`):** Crunches the data to generate the business reports requested by the frontend.



### 5. The Database (`/server/db`)

This is the filing cabinet (PostgreSQL) where all permanent data lives.

* **The Connection:** Tools to securely connect the server to the database and ensure transactions happen safely.
* **The Blueprint (`schema.sql`):** The exact structure of your tables, custom views, and automated triggers.
* **The Seeds:** Starting data to get the app running, including the original business data and the setup for the first Superadmin account.

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
