# ═══════════════════════════════════════════════════════════════════════════
#  AWS Amplify Hosting — frontend
#
#  Reverse-proxies /api/* to CloudFront so the browser sees a single origin.
#  This keeps SameSite=Strict working without a custom domain.
#
#  GitHub connection: Terraform creates the app shell, but linking it to the
#  repo is a one-time manual step in the Amplify console (Authorize GitHub).
#  This is the path AWS recommends — it uses Amplify's GitHub App rather
#  than a long-lived personal access token.
# ═══════════════════════════════════════════════════════════════════════════

resource "aws_amplify_app" "frontend" {
  name        = "${local.name_prefix}-frontend"
  description = "HopePMS Astro SSR frontend"

  # NOTE: `repository` is deliberately omitted. Setting it would require us to
  # also pass either an `access_token` (long-lived GitHub PAT) or `oauth_token`
  # — both of which would land in Terraform state. Instead, we create an
  # unconnected app shell here and connect GitHub via the Amplify GitHub App
  # in the AWS console (one-time, ~30 seconds). See infra/README.md step 3.

  # Astro SSR uses Amplify's "WEB_COMPUTE" platform automatically when it
  # detects output:'server'. We set it explicitly so Terraform doesn't show a
  # diff after first apply.
  platform = "WEB_COMPUTE"

  # The amplify.yml in the repo root takes precedence; this is fallback.
  build_spec = <<-YAML
    version: 1
    applications:
      - appRoot: .
        frontend:
          phases:
            preBuild:
              commands:
                - npm ci --prefer-offline --no-audit --fund=false
            build:
              commands:
                - npm run build
          artifacts:
            baseDirectory: dist
            files: ['**/*']
          cache:
            paths:
              - node_modules/**/*
              - .astro/**/*
  YAML

  enable_branch_auto_build       = true
  enable_branch_auto_deletion    = false

  environment_variables = {
    # Same-origin path — Amplify rewrite below maps /api → CloudFront.
    PUBLIC_API_BASE_URL = "/api"
    AMPLIFY_DIFF_DEPLOY = "false"
    NODE_OPTIONS        = "--max-old-space-size=4096"
  }

  # Reverse-proxy /api/* to CloudFront. This is the keystone of the
  # same-origin cookie strategy (see ADR-001).
  custom_rule {
    source = "/api/<*>"
    target = "https://${aws_cloudfront_distribution.api.domain_name}/api/<*>"
    status = "200"  # rewrite (proxy), NOT redirect
  }

  # SPA-style fallback for any non-file route that doesn't match Astro pages.
  # Astro SSR usually handles this server-side, but the rule is harmless.
  custom_rule {
    source = "</^[^.]+$|\\.(?!(css|gif|ico|jpg|js|png|txt|svg|woff|woff2|ttf|map|json|webp)$)([^.]+$)/>"
    target = "/index.html"
    status = "200"
  }

  tags = { Name = "${local.name_prefix}-frontend" }
}

resource "aws_amplify_branch" "main" {
  app_id      = aws_amplify_app.frontend.id
  branch_name = var.github_oidc_branch   # same branch the deploy workflow trusts

  framework = "Astro"
  stage     = "PRODUCTION"

  enable_auto_build         = true
  enable_pull_request_preview = false

  tags = { Name = "${local.name_prefix}-frontend-${var.github_oidc_branch}" }
}
