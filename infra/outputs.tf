# ═══════════════════════════════════════════════════════════════════════════
#  Outputs you'll need after `terraform apply`:
#    - The CloudFront domain → paste into the Amplify rewrite rule.
#    - The ECR registry URI + repo name → for the GitHub Actions workflow.
#    - The GitHub OIDC role ARN → save as a GitHub Actions secret.
#    - The ECS cluster + service names → also referenced by the workflow.
#    - The RDS endpoint → for bastion / psql access during bootstrap.
#  Note: Amplify is created manually in the AWS console (not by Terraform).
# ═══════════════════════════════════════════════════════════════════════════

output "cloudfront_api_domain" {
  description = "CloudFront distribution fronting the ALB. Use this as the target of the Amplify /api/* rewrite rule."
  value       = aws_cloudfront_distribution.api.domain_name
}

output "alb_dns_name" {
  description = "ALB DNS — direct access returns 403 (CloudFront-only). For debugging."
  value       = aws_lb.backend.dns_name
}

output "ecr_repository_url" {
  description = "ECR repo URI for the backend image."
  value       = aws_ecr_repository.backend.repository_url
}

output "ecr_repository_name" {
  description = "ECR repo name (used as the ECR_REPOSITORY env var in the workflow)."
  value       = aws_ecr_repository.backend.name
}

output "ecs_cluster_name" {
  description = "ECS cluster name (used by deploy-backend.yml)."
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "ECS service name (used by deploy-backend.yml)."
  value       = aws_ecs_service.backend.name
}

output "ecs_task_family" {
  description = "ECS task family (used by deploy-backend.yml)."
  value       = aws_ecs_task_definition.backend.family
}

output "github_actions_deployer_role_arn" {
  description = "OIDC role ARN for GitHub Actions. Used by the workflow when assuming AWS credentials."
  value       = aws_iam_role.github_deployer.arn
}

output "aws_account_id" {
  description = "AWS Account ID. Save as the GitHub Actions `AWS_ACCOUNT_ID` repo secret."
  value       = local.account_id
}

output "rds_endpoint" {
  description = "RDS PostgreSQL endpoint (private subnet — reachable only from ECS or a bastion in the VPC)."
  value       = aws_db_instance.main.address
}

output "rds_db_name" {
  description = "Initial database name created by RDS."
  value       = aws_db_instance.main.db_name
}

output "redis_primary_endpoint" {
  description = "ElastiCache primary endpoint."
  value       = aws_elasticache_replication_group.main.primary_endpoint_address
}

output "secret_db_arn" {
  description = "Secrets Manager ARN for DB credentials."
  value       = aws_secretsmanager_secret.db.arn
}

output "secret_auth0_arn" {
  description = "Secrets Manager ARN for Auth0 credentials. Update with the real client secret after creating the Auth0 application."
  value       = aws_secretsmanager_secret.auth0.arn
}

output "amplify_setup_instructions" {
  description = "Step-by-step for the Amplify console after `terraform apply` succeeds."
  value = {
    step_1_create_app   = "AWS Console → Amplify → Host a web app → GitHub → authorize Amplify GitHub App"
    step_2_repo_branch  = "Pick repo + branch — Amplify auto-detects amplify.yml from the repo root"
    step_3_env_var      = "App settings → Environment variables → PUBLIC_API_BASE_URL = /api"
    step_4_rewrite_rule = "App settings → Rewrites and redirects → Source: /api/<*> · Target: https://${aws_cloudfront_distribution.api.domain_name}/api/<*> · Status: 200 (Rewrite)"
    step_5_capture_url  = "After first build succeeds, copy the assigned URL (https://<branch>.<id>.amplifyapp.com) into terraform.tfvars as `amplify_origin`, then re-run terraform apply"
  }
}

output "auth0_setup_instructions" {
  description = "Paste these into Auth0 after step 5 above — but substitute `<amplify-url>` with the URL you got from Amplify."
  value = {
    application_type    = "Regular Web Application"
    callback_url        = "<amplify-url>/api/auth/callback"
    logout_url          = "<amplify-url>/login"
    allowed_web_origin  = "<amplify-url>"
    audience            = var.auth0_audience
    after_setup_command = "aws secretsmanager update-secret --region ${var.aws_region} --secret-id ${aws_secretsmanager_secret.auth0.name} --secret-string '{\"AUTH0_CLIENT_SECRET\":\"<paste-from-auth0>\"}'"
  }
}
