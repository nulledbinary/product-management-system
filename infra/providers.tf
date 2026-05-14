provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = "hopepms"
    }
  }
}

# CloudFront-tier resources (ACM, etc.) must live in us-east-1. We don't
# attach a custom ACM cert to CloudFront in this build — the default
# *.cloudfront.net cert is fine — but the aliased provider is wired in for
# future use.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = "hopepms"
    }
  }
}
