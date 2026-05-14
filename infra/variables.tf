variable "aws_region" {
  description = "AWS region for all resources."
  type        = string
  default     = "ap-southeast-1"
}

variable "project_name" {
  description = "Short identifier used as a name prefix on every resource."
  type        = string
  default     = "hopepms"
}

variable "environment" {
  description = "Logical environment tag (prod, staging, dev)."
  type        = string
  default     = "prod"
}

# ─── GitHub (for the deploy-backend OIDC role) ────────────────────────────
variable "github_org" {
  description = "GitHub organization or user that owns the HopePMS repository."
  type        = string
}

variable "github_repo" {
  description = "GitHub repository name (without org prefix)."
  type        = string
  default     = "productmanagement"
}

variable "github_oidc_branch" {
  description = "Branch allowed to assume the deployer role via OIDC."
  type        = string
  default     = "main"
}

# ─── Networking ───────────────────────────────────────────────────────────
variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.42.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDRs for public subnets (one per AZ)."
  type        = list(string)
  default     = ["10.42.0.0/20", "10.42.16.0/20"]
}

variable "private_subnet_cidrs" {
  description = "CIDRs for private subnets (one per AZ)."
  type        = list(string)
  default     = ["10.42.32.0/20", "10.42.48.0/20"]
}

# ─── RDS PostgreSQL ───────────────────────────────────────────────────────
variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage_gb" {
  description = "Allocated storage for RDS, in GB."
  type        = number
  default     = 20
}

variable "db_name" {
  description = "Initial database name."
  type        = string
  default     = "hopedb"
}

variable "db_username" {
  description = "Master DB user (Flyway runs as this user; you can rotate to a least-privilege app user post-bootstrap)."
  type        = string
  default     = "hopepms_app"
}

# ─── ElastiCache Redis ────────────────────────────────────────────────────
variable "redis_node_type" {
  description = "ElastiCache node type."
  type        = string
  default     = "cache.t4g.micro"
}

# ─── ECS Fargate ──────────────────────────────────────────────────────────
variable "ecs_task_cpu" {
  description = "Fargate task CPU units (1024 = 1 vCPU)."
  type        = number
  default     = 512
}

variable "ecs_task_memory_mb" {
  description = "Fargate task memory in MiB."
  type        = number
  default     = 1024
}

variable "ecs_desired_count" {
  description = "Desired number of running ECS tasks."
  type        = number
  default     = 2
}

# ─── Auth0 placeholder (real value set in Secrets Manager after Auth0 setup) ─
variable "auth0_client_secret_placeholder" {
  description = "Placeholder secret used at first apply. Update Secrets Manager once Auth0 is configured."
  type        = string
  default     = "REPLACE-ME-AFTER-AUTH0-SETUP"
  sensitive   = true
}
