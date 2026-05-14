resource "aws_elasticache_subnet_group" "main" {
  name       = "${local.name_prefix}-redis"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_parameter_group" "redis7" {
  name        = "${local.name_prefix}-redis7"
  family      = "redis7"
  description = "HopePMS Redis defaults"
}

# Single-node replication group with TLS-in-transit + at-rest encryption.
# Multi-AZ + automatic failover would double the cost; for this workload
# (volatile sessions are recoverable by re-login) single-node is acceptable.
resource "aws_elasticache_replication_group" "main" {
  replication_group_id       = "${local.name_prefix}-redis"
  description                = "HopePMS volatile session store"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.redis_node_type
  parameter_group_name       = aws_elasticache_parameter_group.redis7.name
  subnet_group_name          = aws_elasticache_subnet_group.main.name
  security_group_ids         = [aws_security_group.redis.id]
  port                       = 6379

  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true

  snapshot_retention_limit = 0
  apply_immediately        = false

  tags = { Name = "${local.name_prefix}-redis" }
}
