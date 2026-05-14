# HopePMS — Astro (Amplify) + Spring Boot (ECS) Edition

Hope, Inc. Product Management System (HopePMS). A secure, role-aware product
management web app for the HopeDB schema.

> **Stack:** Astro 4 (SSR · Node adapter) · Tailwind CSS · Nano Stores · Auth0
> (Authorization Code + PKCE, server-side) · Spring Boot 3.4 / Java 21 ·
> Amazon ECS · ElastiCache for Redis · Amazon RDS PostgreSQL · AWS Amplify

---

## Architecture at a glance

```text
                ┌─────────────────────────────────────┐
                │     AWS Amplify (Astro SSR)         │
                │  hopepms.example.com                │
                │  · Service-catalog UI               │
                │  · Volatile session atom (Nano)     │
                │  · No client-readable tokens        │
                └──────────────┬──────────────────────┘
                               │ HttpOnly cookie (HPMS_SID)
                               ▼
                ┌─────────────────────────────────────┐
                │  Amazon ECS — Spring Boot API       │
                │  api.hopepms.example.com            │
                │  · /api/auth/* (PKCE handshake)     │
                │  · /api/products/* /admin/* /reports│
                │  · @RequiresRight enforcement       │
                └────────┬───────────────────┬────────┘
                         │                   │
                ┌────────▼──────┐   ┌────────▼──────────┐
                │  ElastiCache  │   │  Amazon RDS PG    │
                │  for Redis    │   │  hopedb schema    │
                │  · session    │   │  · soft-delete    │
                │    records    │   │    triggers       │
                │  · PKCE state │   │  · SUPERADMIN     │
                └───────────────┘   │    protection     │
                                    └───────────────────┘
                                                ▲
                                                │ Authorization Code + PKCE
                                                │
                                       ┌────────┴────────┐
                                       │     Auth0       │
                                       │  · Email + pwd  │
                                       │  · Google OAuth │
                                       └─────────────────┘
```

## Repository layout

```text
.
├── amplify.yml                         # Amplify build pipeline (Astro SSR)
├── .github/workflows/deploy-backend.yml # OIDC → ECR → ECS rolling deploy
├── astro.config.mjs / tailwind.config.mjs / tsconfig.json
├── PROJECT_PLAN.md / HopePMS_Project_Guide_CS.docx.pdf / HopeDB (3).sql
├── public/                              # Static assets
├── src/                                 # Astro frontend (this directory)
│   ├── layouts/  AppShell · BaseLayout
│   ├── components/  Sidebar · TopBar · ProductFormModal · ConfirmModal · ui/Logo
│   ├── pages/    index · login · register · products/* · reports/* · admin/users
│   └── lib/
│       ├── auth/        volatileSession.ts · rights.ts
│       ├── security/    pathGuard.ts · sanitize.ts
│       ├── api/         client.ts · products.ts · users.ts
│       └── utils/       stamp.ts
└── backend/                             # Spring Boot 3.4 on Amazon ECS
    ├── Dockerfile · pom.xml · mvnw
    └── src/main
        ├── java/com/hopepms
        │   ├── HopePmsApplication.java
        │   ├── config/      SecurityConfig · WebConfig · HopePmsProperties
        │   ├── security/    VolatileSessionFilter · VolatileSessionStore
        │   │                 RequiresRight + Aspect · HopePrincipal
        │   ├── auth/        Auth0Service · AuthController · ProvisioningService
        │   ├── domain/
        │   │   ├── products/    ProductController · PriceHistController · Repository
        │   │   ├── reports/     REP_001 / REP_002
        │   │   └── users/       AdminUsersController
        │   └── util/        StampHelper · ApiException · GlobalExceptionHandler
        └── resources
            ├── application.yml
            └── db/migration/    V1…V5 — schema · rights · triggers · seed
```

---

## Security model

### 1. Volatile, ephemeral sessions (ADR-001)

- No `localStorage`, `sessionStorage`, IndexedDB, or client-readable cookies.
- Server mints a 256-bit opaque session id, stored in Redis with a TTL.
- Cookie is `HttpOnly; Secure; SameSite=Strict`, **no `Max-Age` / `Expires`** —
  the browser drops it when the tab closes.
- Client identity lives in a Nano Stores `atom` (plain JS heap, gone on refresh).
- `beforeunload`, `pagehide`, and `visibilitychange(hidden)` POST to
  `/api/auth/invalidate` via `navigator.sendBeacon` so Redis is cleaned up
  immediately; the server-side TTL is the safety net.
- **SUPERADMIN TTL = 8 minutes, no sliding.** USER/ADMIN = 30 minutes,
  sliding on each request.

### 2. No DELETE — anywhere

- The application code never issues `DELETE`. Soft-delete is `UPDATE … SET
  record_status='INACTIVE'`.
- The Postgres trigger `reject_hard_delete()` (migration V3) raises on any
  `DELETE` against `product`, `priceHist`, or `user` — belt and braces.

### 3. SUPERADMIN protection at three layers

1. UI: action buttons disabled on SUPERADMIN rows with tooltip.
2. API: `AdminUsersController.setStatus` 403s if target is SUPERADMIN and
   caller isn't.
3. DB: `enforce_superadmin_protection()` trigger reads `hopepms.caller_userid`
   (set via `set_config()` in the same transaction) and refuses any
   non-SUPERADMIN write against a SUPERADMIN row.

### 4. Audit stamps

Format `ACTION userId YYYY-MM-DD HH:MM`, generated server-side by
`StampHelper.make()`. Hidden from USER accounts in both API responses
(`ProductController.sanitize`) and UI (`canSeeStamp`).

---

## Getting started

### Frontend

```bash
cp .env.example .env             # fill in PUBLIC_API_BASE_URL
npm install
npm run dev                      # http://localhost:4321
```

### Backend

```bash
# Start Postgres + Redis locally
docker run -d --name pg    -p 5432:5432 \
  -e POSTGRES_DB=hopedb -e POSTGRES_USER=hopepms -e POSTGRES_PASSWORD=hopepms postgres:16
docker run -d --name redis -p 6379:6379 redis:7-alpine

cd backend
export $(grep -v '^#' ../.env | xargs)
export SESSION_COOKIE_SECURE=false       # local HTTP only
./mvnw spring-boot:run                   # http://localhost:8080
```

Flyway migrations run automatically on first boot and create the `hopedb`
schema, seed the modules/rights catalog, and place a SUPERADMIN placeholder
keyed on `jcesperanza@neu.edu.ph` (see V5). When that user first signs in,
`ProvisioningService` re-keys the row onto the real Auth0 `sub`.

For dev, the frontend at `:4321` needs to reach the API at `:8080`. The
simplest path is an Astro dev-server proxy in `astro.config.mjs` or use
Amplify's local emulator; in production, both are served from the same parent
domain (`hopepms.example.com` and `api.hopepms.example.com`).

---

## Routes & rights

| Page                            | Right needed | Notes                                                |
|---------------------------------|--------------|------------------------------------------------------|
| `/products`                     | (any active) | USER sees ACTIVE only · stamp hidden                 |
| `/products` Add button          | `PRD_ADD`    |                                                      |
| `/products` Edit button         | `PRD_EDIT`   |                                                      |
| `/products` Delete button       | `PRD_DEL`    | Soft delete only                                     |
| `/products/deleted`             | ADMIN+       | Recovery panel                                       |
| `/reports/product-listing`      | `REP_001`    |                                                      |
| `/reports/top-selling`          | `REP_002`    | SUPERADMIN-only per the rights matrix                |
| `/admin/users`                  | `ADM_USER`   | SUPERADMIN rows always read-only for ADMIN callers   |

---

## Deployment

- **Frontend** → AWS Amplify Hosting, Astro SSR compute platform. Build is
  defined in [`amplify.yml`](./amplify.yml) and triggered on push to `main`.
- **Backend** → Amazon ECS Fargate, deployed by
  [`.github/workflows/deploy-backend.yml`](./.github/workflows/deploy-backend.yml).
  The workflow only fires when files under `backend/**` change. OIDC role,
  Docker BuildKit cache from GHA, image tagged `<short-sha>-<run-number>`,
  rolling update via `amazon-ecs-deploy-task-definition` with stability wait.
- **Database** → Amazon RDS PostgreSQL 16. Flyway migrations run on backend
  startup against the configured `DB_URL`.
- **Session store** → ElastiCache for Redis. The cluster needs to be in the
  same VPC as the ECS service.

---

## Definition of Done coverage (PROJECT_PLAN.md)

| Mandate                                       | Where it lives                                                                                      |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------------|
| No DELETE statements                          | Postgres trigger `reject_hard_delete` (V3) + no DELETE in source                                    |
| Soft-deleted invisible to USER                | `ProductController.list` / `getOne` + UI filter                                                     |
| SUPERADMIN protection (UI + API + DB)         | UI disabled state + `AdminUsersController` check + `enforce_superadmin_protection` trigger          |
| Audit stamps on every write                   | `StampHelper.make()` invoked in every repository write path                                         |
| Stamp hidden from USER                        | `ProductController.sanitize` + `canSeeStamp` in UI                                                  |
| Live production URL                           | Amplify (frontend) + ECS service (backend)                                                          |

---

**Prepared by:** Boris Gamaliel D. Duque & Yna Solitario · **Instructor:** Jeremias C. Esperanza
**Institution:** New Era University – College of Computer Studies · **Course:** Software Engineering 2
