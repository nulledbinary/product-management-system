# ═══════════════════════════════════════════════════════════════════════════
#  CloudFront in front of the ALB
#
#  Why CloudFront exists in this stack: without a custom domain we can't
#  attach a real ACM cert to the ALB. CloudFront provides the default
#  *.cloudfront.net certificate, terminating TLS in front of an HTTP-only
#  ALB. Amplify rewrites /api/* to this distribution so the browser sees a
#  single origin (the Amplify domain) and the session cookie binds there.
# ═══════════════════════════════════════════════════════════════════════════

# Forward (almost) everything to the ALB. Auth-bearing endpoints must not be
# cached — that's enforced by the no-cache policy below.
data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

resource "aws_cloudfront_distribution" "api" {
  enabled         = true
  comment         = "${local.name_prefix} API edge (Amplify → CloudFront → ALB → ECS)"
  is_ipv6_enabled = true
  http_version    = "http2and3"
  price_class     = "PriceClass_200"  # NA + EU + Asia (covers ap-southeast-1)

  origin {
    domain_name = aws_lb.backend.dns_name
    origin_id   = "alb-${local.name_prefix}"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }

    # Shared-secret header that the ALB listener rule looks for. Anyone who
    # finds the ALB DNS directly will get 403 because they can't reproduce this.
    custom_header {
      name  = "X-HopePMS-Edge"
      value = random_password.cloudfront_shared_secret.result
    }
  }

  default_cache_behavior {
    allowed_methods  = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = "alb-${local.name_prefix}"

    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
    minimum_protocol_version       = "TLSv1.2_2021"
  }

  tags = { Name = "${local.name_prefix}-cf-api" }
}
