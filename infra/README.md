# HopePMS Infrastructure (Terraform)

Provisions every AWS resource HopePMS needs in **ap-southeast-1**: VPC,
RDS PostgreSQL (Multi-AZ), ElastiCache Redis, ECR, ECS Fargate, ALB +
CloudFront, Amplify Hosting, IAM, Secrets Manager, and the GitHub Actions
OIDC role.

## Architecture

```text
  Browser
    │ HTTPS
    ▼
  AWS Amplify Hosting  ──┐
   (Astro SSR)           │  /api/*  rewrite (200)
                         ▼
                    CloudFront  ──┐
                                  │  HTTP + X-HopePMS-Edge header
                                  ▼
                                 ALB  ──┐
                                        │  (private subnets)
                                        ▼
                                    ECS Fargate ── RDS Postgres
                                    Spring Boot ── ElastiCache Redis
                                       │ Outbound HTTPS via NAT
                                       ▼
                                      Auth0
```

The Amplify domain is the only public-facing hostname users hit. CloudFront
exists solely to give the HTTP-only ALB a TLS endpoint that Amplify can
rewrite to. A shared-secret header (`X-HopePMS-Edge`) ensures the ALB only
accepts traffic that originated from our CloudFront distribution, even
though the ALB itself is internet-facing.

## What gets created

| Layer            | Resources                                                            |
|------------------|----------------------------------------------------------------------|
| Network          | VPC, 2 public + 2 private subnets, IGW, single NAT Gateway, RTs      |
| Edge / TLS       | ALB (HTTP-only, CloudFront SG), CloudFront distribution              |
| Data             | RDS Postgres 16 Multi-AZ, ElastiCache Redis 7 (single node, TLS)     |
| Compute          | ECS Fargate cluster + service + task definition                      |
| Container registry | ECR repository `hopepms/backend` with lifecycle policy             |
| Identity         | Task execution role, task role, GitHub Actions OIDC provider + role  |
| Secrets          | Secrets Manager entries for DB password and Auth0 client secret      |
| Logs             | CloudWatch log group `/ecs/hopepms-backend`                          |
| Frontend         | Amplify app + production branch, with /api/* rewrite to CloudFront   |

## Cost estimate (steady state, ap-southeast-1)

| Item                            | Approx USD/mo |
|---------------------------------|--------------:|
| RDS db.t4g.micro Multi-AZ + gp3 | ~$30          |
| ElastiCache cache.t4g.micro     | ~$13          |
| ALB                             | ~$22          |
| NAT Gateway + EIP               | ~$36          |
| ECS Fargate (2 × 0.5 vCPU / 1GB)| ~$32          |
| CloudFront (low traffic)        | ~$1           |
| Secrets Manager (2 secrets)     | ~$1           |
| CloudWatch Logs (30-day rtn)    | ~$2           |
| Amplify Hosting                 | free tier     |
| **Total**                       | **~$137/mo**  |

NAT Gateway is the easiest single line item to cut: move ECS to public
subnets with `assign_public_ip = true` and you save ~$36/mo at the cost of
giving each task a public IP.

## Apply walkthrough

### 0. Prereqs

```bash
# AWS CLI authenticated as a user with admin (or sufficient) IAM rights
aws sts get-caller-identity

# Terraform ≥ 1.7
terraform -version
```

### 1. Configure variables

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars: set `github_org` to your GitHub org/user.
# All other variables have sensible defaults.
```

### 2. First apply

```bash
terraform init
terraform apply
```

The first apply takes ~25 minutes (RDS Multi-AZ + ElastiCache + CloudFront
each take 10+ minutes individually). Capture all outputs — you'll need
`amplify_branch_url`, `ecr_repository_url`, and `auth0_setup_hint`.

### 3. Connect the Amplify app to GitHub (one-time, console)

Terraform can't perform the GitHub OAuth handshake on your behalf without
a long-lived PAT, which we deliberately avoid. In the AWS console:

1. AWS Amplify → Apps → `hopepms-prod-frontend`
2. **Connect a repository** → GitHub → authorize the Amplify GitHub App
3. Pick `productmanagement` and the `main` branch
4. Confirm the build settings (Amplify auto-detects [`amplify.yml`](../amplify.yml))
5. Save and deploy

The first build will run automatically.

### 4. Set up Auth0

In the Auth0 dashboard:

1. **Create Application** → **Regular Web Application** (NOT SPA)
2. Settings tab — paste from the `auth0_setup_hint` Terraform output:
   - **Allowed Callback URLs**: `https://<branch>.<amplify-default-domain>/api/auth/callback`
   - **Allowed Logout URLs**: `https://<branch>.<amplify-default-domain>/login`
   - **Allowed Web Origins**: `https://<branch>.<amplify-default-domain>`
   - **Token Endpoint Authentication Method**: `Post`
   - **Grant Types**: enable Authorization Code, disable Refresh Token
3. **Applications → APIs → Create API**:
   - Identifier (audience): `https://api.hopepms.local` (or whatever you set in `auth0_setup_hint`)
   - Signing Algorithm: `RS256`
4. **Authentication → Database**: enable username/password connection
5. **Authentication → Social → Google** (optional): provide Google Cloud OAuth credentials

Capture from the Auth0 application settings page:

- `AUTH0_DOMAIN` (e.g. `dev-xxx.us.auth0.com`)
- `AUTH0_CLIENT_ID`
- `AUTH0_CLIENT_SECRET`

### 5. Push the real Auth0 values into Secrets Manager + task definition

```bash
# Put the real client secret into Secrets Manager
aws secretsmanager update-secret \
  --region ap-southeast-1 \
  --secret-id hopepms-prod/auth0 \
  --secret-string '{"AUTH0_CLIENT_SECRET":"<paste-from-auth0>"}'
```

The Auth0 *domain*, *client ID*, *audience*, *redirect URI*, and
*logout URL* are baked into the ECS task definition as plain env vars (not
secrets). Update them by re-applying Terraform with these variables set —
or easier, edit the env vars in `infra/ecs.tf` (in the `container_env`
local) and run `terraform apply` again, then force a service redeploy:

```bash
aws ecs update-service \
  --region ap-southeast-1 \
  --cluster hopepms-prod \
  --service hopepms-backend \
  --force-new-deployment
```

### 6. Wire GitHub Actions

In the GitHub repository, **Settings → Secrets and variables → Actions**:

- Add repo secret `AWS_ACCOUNT_ID` with the value from `terraform output aws_account_id`.
- Optionally create environment `production` with required reviewers.

The workflow at `.github/workflows/deploy-backend.yml` will fire on the
next push that touches `backend/**`. It uses the OIDC role created here:

```bash
terraform output github_actions_deployer_role_arn
# arn:aws:iam::<account>:role/github-actions-ecs-deployer
```

### 7. Smoke tests

```bash
# Pure health (skips the auth chain)
curl https://<cloudfront-domain>/actuator/health
# Expect: {"status":"UP"}

# Through the Amplify rewrite (this is what the browser sees)
curl https://<branch>.<amplify-domain>/api/actuator/health

# Auth start — should 302 to Auth0
curl -I https://<branch>.<amplify-domain>/api/auth/start

# Open the app
open https://<branch>.<amplify-domain>
```

## Common operations

### Re-deploy the backend manually

```bash
git commit --allow-empty -m "redeploy backend" && git push
```

### Rotate the database master password

The password is generated by Terraform and stored in Secrets Manager.
`aws_db_instance.main` has `lifecycle.ignore_changes = [password]` so
Terraform won't fight you. To rotate:

```bash
# 1. Generate new password
new_pw=$(openssl rand -base64 30 | tr -d '=+/' | head -c 32)

# 2. Update RDS
aws rds modify-db-instance --db-instance-identifier hopepms-prod-db \
  --master-user-password "$new_pw" --apply-immediately

# 3. Update Secrets Manager (preserve the URL/USER fields)
db_arn=$(terraform output -raw secret_db_arn)
existing=$(aws secretsmanager get-secret-value --secret-id "$db_arn" --query SecretString --output text)
new_payload=$(echo "$existing" | jq --arg pw "$new_pw" '.DB_PASSWORD = $pw')
aws secretsmanager update-secret --secret-id "$db_arn" --secret-string "$new_payload"

# 4. Force ECS to pick up the new secret
aws ecs update-service --cluster hopepms-prod --service hopepms-backend --force-new-deployment
```

### Tear down

```bash
# RDS has deletion_protection = true; disable it first.
aws rds modify-db-instance --db-instance-identifier hopepms-prod-db \
  --no-deletion-protection --apply-immediately

terraform destroy
```

CloudFront takes ~15 minutes to delete (distributions are slow to disable
worldwide). ECR will refuse to delete if images exist — `terraform destroy`
will pause and you'll need to `aws ecr batch-delete-image` first or set
`force_delete = true` on the repo.

## Troubleshooting

**Amplify build fails with `Cannot find module`** — Amplify caches
`node_modules`. Clear the cache from the console: **App settings → Build
settings → Clear cache**.

**ECS task fails health check** — confirm via `aws ecs describe-services`
that the task is reaching `RUNNING`. If it's `STOPPED`, the CloudWatch log
group `/ecs/hopepms-backend` has the Spring Boot startup logs. Most common
causes: wrong `DB_URL` (Multi-AZ failover changes the writer endpoint —
shouldn't happen on first apply), Auth0 placeholder values still in env
vars, or Redis connection refused (security group misconfigured).

**Browser sees `403 direct access forbidden`** — you're hitting the ALB
DNS directly. Use the Amplify URL or the CloudFront domain. This is
working as designed.

**Auth0 redirect loop** — the `AUTH0_REDIRECT_URI` env var on the ECS task
must exactly match what's listed in Auth0's Allowed Callback URLs.
Trailing slashes matter.

**"AssumeRoleWithWebIdentity not authorized"** in GitHub Actions — the
trust policy's `sub` condition must match exactly. Re-check that
`var.github_org` and `var.github_repo` are right; the role's trust
relationship uses them verbatim.
