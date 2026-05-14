resource "aws_cloudwatch_log_group" "ecs" {
  name              = "/ecs/${local.ecs_service_name}"
  retention_in_days = 30

  tags = { Name = "/ecs/${local.ecs_service_name}" }
}
