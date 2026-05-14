# ═══════════════════════════════════════════════════════════════════════════
#  Application Load Balancer
#
#  Internet-facing but locked down at the SG layer to CloudFront's prefix
#  list (see security_groups.tf). Listens HTTP/80 only — CloudFront does TLS
#  termination. A shared-secret header validates that requests originated
#  from our CloudFront distribution and not directly from the open internet.
# ═══════════════════════════════════════════════════════════════════════════

resource "random_password" "cloudfront_shared_secret" {
  length  = 48
  special = false
}

resource "aws_lb" "backend" {
  name               = "${local.name_prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  drop_invalid_header_fields = true
  enable_http2               = true
  idle_timeout               = 60

  tags = { Name = "${local.name_prefix}-alb" }
}

resource "aws_lb_target_group" "backend" {
  name        = "${local.name_prefix}-tg"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  deregistration_delay = 30

  health_check {
    path                = "/actuator/health/liveness"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = { Name = "${local.name_prefix}-tg" }
}

# Default action: anything that lacks the shared-secret header gets 403.
# Listener rule below grants 200-class forwarding only when the header matches.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.backend.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      status_code  = "403"
      message_body = "direct access forbidden"
    }
  }

  tags = { Name = "${local.name_prefix}-listener-http" }
}

resource "aws_lb_listener_rule" "forward_from_cloudfront" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 100

  condition {
    http_header {
      http_header_name = "X-HopePMS-Edge"
      values           = [random_password.cloudfront_shared_secret.result]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
}
