# ═══════════════════════════════════════════════════════════════════════════
#  Outputs you'll need after `terraform apply`:
#    - The Amplify domain → feeds Auth0 Allowed Callback / Logout URLs and
#      the env vars on the ECS task.
#    - The ECR registry URI + repo name → for the GitHub Actions workflow.
#    - The GitHub OIDC role ARN → set as a GitHub secret.
#    - The ECS cluster + service names → also referenced by the workflow.
#    - The CloudFront domain → for debugging only; users never hit it directly.
#    - The RDS endpoint → for bastion / psql access during bootstrap.
# ═══════════════════════════════════════════════════════════════════════════

output "amplify_app_id" {
  description = "Amplify App ID — used to connect the GitHub repo in the console."
  value       = aws_amplify_app.frontend.id
}

output "amplify_default_domain" {
  description = "Amplify-assigned domain. The frontend lives at https://<branch>.<this>."
  value       = aws_amplify_app.frontend.default_domain
}

output "amplify_branch_url" {
  description = "Full public URL of the deployed branch."
  value       = "https://${aws_amplify_branch.main.branch_name}.${aws_amplify_app.frontend.default_domain}"
}

output "cloudfront_api_domain" {
  description = "CloudFront distribution fronting the ALB. Amplify rewrites /api/* here."
  value       = aws_cloudfront_distribution.api.domain_name
}

output "alb_dns_name" {
  description = "Internal ALB DNS — direct access returns 403 (CloudFront-only)."
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
  description = "OIDC role for GitHub Actions. Set this on the workflow side; the workflow's AWS_ACCOUNT_ID secret derives from it."
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
  description = "Secrets Manager ARN for Auth0 credentials. UPDATE THIS after creating the Auth0 application."
  value       = aws_secretsmanager_secret.auth0.arn
}

output "auth0_setup_hint" {
  description = "Paste these into the Auth0 application settings."
  value = {
    application_type    = "Regular Web Application"
    callback_url        = "https://${aws_amplify_branch.main.branch_name}.${aws_amplify_app.frontend.default_domain}/api/auth/callback"
    logout_url          = "https://${aws_amplify_branch.main.branch_name}.${aws_amplify_app.frontend.default_domain}/login"
    allowed_web_origin  = "https://${aws_amplify_branch.main.branch_name}.${aws_amplify_app.frontend.default_domain}"
    audience            = var.auth0_audience
    after_setup_command = "aws secretsmanager update-secret --region ${var.aws_region} --secret-id ${aws_secretsmanager_secret.auth0.name} --secret-string '{\"AUTH0_CLIENT_SECRET\":\"<paste-from-auth0>\"}'"
  }
}
