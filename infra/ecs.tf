# ═══════════════════════════════════════════════════════════════════════════
#  ECS Fargate — cluster + task definition + service
#
#  The task definition's image is set to a placeholder for the first apply
#  (terraform doesn't push images). The GitHub Actions workflow registers a
#  new task definition revision with the real image on every backend push;
#  `lifecycle.ignore_changes = [container_definitions]` keeps Terraform from
#  fighting the deploy pipeline.
# ═══════════════════════════════════════════════════════════════════════════

resource "aws_ecs_cluster" "main" {
  name = local.ecs_cluster_name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = { Name = local.ecs_cluster_name }
}

resource "aws_ecs_cluster_capacity_providers" "fargate" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
    base              = 1
  }
}

# Public placeholder image — used only for the first task-def revision.
# Spring Boot is *not* expected to come up healthy until GitHub Actions has
# pushed a real backend image; that's normal.
locals {
  bootstrap_image = "public.ecr.aws/amazonlinux/amazonlinux:2023"

  amplify_origin = "https://${aws_amplify_branch.main.branch_name}.${aws_amplify_app.frontend.default_domain}"

  container_env = [
    { name = "DB_USER",                value = var.db_username },
    { name = "REDIS_HOST",             value = aws_elasticache_replication_group.main.primary_endpoint_address },
    { name = "REDIS_PORT",             value = "6379" },
    { name = "REDIS_SSL",              value = "true" },
    { name = "AUTH0_DOMAIN",           value = var.auth0_domain },
    { name = "AUTH0_CLIENT_ID",        value = var.auth0_client_id },
    { name = "AUTH0_AUDIENCE",         value = var.auth0_audience },
    { name = "AUTH0_REDIRECT_URI",     value = "${local.amplify_origin}/api/auth/callback" },
    { name = "AUTH0_LOGOUT_RETURN_TO", value = "${local.amplify_origin}/login" },
    { name = "CORS_ALLOWED_ORIGINS",   value = local.amplify_origin },
    { name = "SESSION_COOKIE_SECURE",  value = "true" },
    { name = "SERVER_FORWARD_HEADERS_STRATEGY", value = "framework" },
  ]

  container_secrets = [
    { name = "DB_URL",              valueFrom = "${aws_secretsmanager_secret.db.arn}:DB_URL::" },
    { name = "DB_PASSWORD",         valueFrom = "${aws_secretsmanager_secret.db.arn}:DB_PASSWORD::" },
    { name = "AUTH0_CLIENT_SECRET", valueFrom = "${aws_secretsmanager_secret.auth0.arn}:AUTH0_CLIENT_SECRET::" },
  ]
}

resource "aws_ecs_task_definition" "backend" {
  family                   = local.ecs_task_family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.ecs_task_cpu)
  memory                   = tostring(var.ecs_task_memory_mb)
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = local.ecs_container_name
    image     = local.bootstrap_image
    essential = true

    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    environment = local.container_env
    secrets     = local.container_secrets

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.ecs.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "spring"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://127.0.0.1:8080/actuator/health/liveness | grep -q UP || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
  }])

  lifecycle {
    # The deploy-backend workflow registers new revisions on every push; do
    # not let `terraform apply` race the pipeline.
    ignore_changes = [container_definitions]
  }

  tags = { Name = local.ecs_task_family }
}

resource "aws_ecs_service" "backend" {
  name                   = local.ecs_service_name
  cluster                = aws_ecs_cluster.main.id
  task_definition        = aws_ecs_task_definition.backend.arn
  desired_count          = var.ecs_desired_count
  launch_type            = "FARGATE"
  platform_version       = "LATEST"
  enable_execute_command = true

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = local.ecs_container_name
    container_port   = 8080
  }

  deployment_controller {
    type = "ECS"
  }

  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  health_check_grace_period_seconds = 90

  lifecycle {
    # The deploy pipeline rotates this every time it ships a new image.
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_lb_listener_rule.forward_from_cloudfront]

  tags = { Name = local.ecs_service_name }
}
