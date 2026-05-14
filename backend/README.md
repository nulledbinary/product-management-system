# HopePMS Backend — Spring Boot on Amazon ECS

The HopePMS backend is a Spring Boot 3.4 application that owns:

- **Auth0 server-side flow** — Authorization Code + PKCE; the SPA SDK is never
  loaded in the browser. Tokens never reach the client.
- **Volatile sessions** — opaque session ids minted server-side, stored in
  ElastiCache for Redis, exchanged with the browser via an
  `HttpOnly; Secure; SameSite=Strict` cookie with **no `Max-Age` / `Expires`**
  (browser drops it on tab close). Standard TTL = 30 min, SUPERADMIN TTL =
  **8 min** (privileged-credential window).
- **Rights enforcement** — `@RequiresRight("PRD_DEL")` etc., evaluated by
  `RequiresRightAspect` against the principal's right set.
- **Data access** — Spring `JdbcClient` over Amazon RDS PostgreSQL, with
  Flyway migrations under `src/main/resources/db/migration/`.
- **Defence-in-depth triggers** — the `reject_hard_delete()` and
  `enforce_superadmin_protection()` triggers refuse hard deletes and any
  ADMIN write against a SUPERADMIN row, even if the application logic is
  bypassed.

## Project layout

```
backend/
├── Dockerfile                            # Multi-stage, layered Spring Boot jar
├── pom.xml                               # Spring Boot 3.4 / Java 21
├── mvnw + .mvn/                          # Maven wrapper (bootstrapped on first run)
└── src/main
    ├── java/com/hopepms
    │   ├── HopePmsApplication.java
    │   ├── config/                       # SecurityConfig, WebConfig, properties
    │   ├── security/                     # VolatileSessionFilter/Store, @RequiresRight
    │   ├── auth/                         # Auth0Service, AuthController, ProvisioningService
    │   ├── domain/
    │   │   ├── products/                 # ProductController + PriceHistController
    │   │   ├── reports/                  # REP_001 / REP_002
    │   │   └── users/                    # Admin user management
    │   └── util/                         # StampHelper, ApiException, GlobalExceptionHandler
    └── resources
        ├── application.yml               # Env-driven config
        └── db/migration/                 # V1__core, V2__rights, V3__triggers, V4__seed, V5__superadmin
```

## Local development

```bash
# 1. Start dependencies (Postgres + Redis) — example via docker compose
docker run -d --name pg -p 5432:5432 -e POSTGRES_DB=hopedb -e POSTGRES_USER=hopepms -e POSTGRES_PASSWORD=hopepms postgres:16
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 2. Export env vars from ../.env (Auth0 creds, etc.)
export $(grep -v '^#' ../.env | xargs)
export SESSION_COOKIE_SECURE=false   # local HTTP only

# 3. Run
./mvnw spring-boot:run
```

Flyway runs all five migrations on first boot, creating the `hopedb` schema,
seeding the modules/rights catalog, and inserting the SUPERADMIN placeholder
row keyed on `jcesperanza@neu.edu.ph`. When that user first signs in,
`ProvisioningService` rebinds the row's `userId` to the real Auth0 `sub`.

## Deployment

Pushed to ECS by [`.github/workflows/deploy-backend.yml`](../.github/workflows/deploy-backend.yml).
The pipeline:

1. Authenticates to AWS via OIDC (no static keys).
2. Builds the multi-stage Docker image with BuildKit cache from GHA.
3. Tags the image `<short-sha>-<run-number>` + `latest` and pushes to ECR.
4. Renders a new ECS task definition with that image.
5. Rolls out via `amazon-ecs-deploy-task-definition` with `wait-for-service-stability`.

## Auth0 configuration checklist

In the Auth0 dashboard, the application must be a **Regular Web Application**
(not SPA — we own the client secret server-side):

- **Allowed Callback URLs**: `https://api.hopepms.example.com/api/auth/callback`
- **Allowed Logout URLs**: `https://hopepms.example.com/login`
- **Token Endpoint Authentication Method**: `Post`
- **Grant Types**: Authorization Code + Refresh Token off (we don't keep refresh
  tokens — sessions are volatile by design)
- The API identifier set in `AUTH0_AUDIENCE` must exist as an API in Auth0.
- Google social connection enabled if you want Google sign-in.
