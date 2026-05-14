data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name_prefix = "${var.project_name}-${var.environment}"
  account_id  = data.aws_caller_identity.current.account_id
  partition   = data.aws_partition.current.partition
  azs         = slice(data.aws_availability_zones.available.names, 0, 2)

  ecr_repository_name = "${var.project_name}/backend"
  ecs_cluster_name    = "${var.project_name}-${var.environment}"
  ecs_service_name    = "${var.project_name}-backend"
  ecs_task_family     = "${var.project_name}-backend"
  ecs_container_name  = "spring-boot-api"
}
