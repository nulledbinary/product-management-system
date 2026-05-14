resource "aws_db_subnet_group" "main" {
  name       = "${local.name_prefix}-rds"
  subnet_ids = aws_subnet.private[*].id

  tags = { Name = "${local.name_prefix}-rds" }
}

# RDS deprecates minor versions over time and availability varies per region.
# Let Terraform pick the highest version that's actually offered in this
# region from a preferred list (newest first). The result is stable across
# applies as long as one of these stays available.
data "aws_rds_engine_version" "postgres" {
  engine             = "postgres"
  preferred_versions = ["16.6", "16.4", "16.3", "16.2", "16.1"]
}

resource "aws_db_parameter_group" "postgres16" {
  name        = "${local.name_prefix}-pg16"
  family      = "postgres16"
  description = "HopePMS Postgres params: force SSL"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "random_password" "db_master" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_db_instance" "main" {
  identifier             = "${local.name_prefix}-db"
  engine                 = "postgres"
  engine_version         = data.aws_rds_engine_version.postgres.version
  instance_class         = var.db_instance_class
  allocated_storage      = var.db_allocated_storage_gb
  max_allocated_storage  = var.db_allocated_storage_gb * 4
  storage_type           = "gp3"
  storage_encrypted      = true
  db_name                = var.db_name
  username               = var.db_username
  password               = random_password.db_master.result
  parameter_group_name   = aws_db_parameter_group.postgres16.name
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  multi_az                = true
  publicly_accessible     = false
  backup_retention_period = 7
  backup_window           = "16:00-17:00"   # off-hours in PH (UTC+8 → 00:00)
  maintenance_window      = "Sun:17:30-Sun:19:30"
  deletion_protection     = true
  skip_final_snapshot     = false
  final_snapshot_identifier = "${local.name_prefix}-db-final-${formatdate("YYYYMMDDhhmm", timestamp())}"

  performance_insights_enabled = true
  performance_insights_retention_period = 7
  copy_tags_to_snapshot                 = true

  apply_immediately = false

  lifecycle {
    ignore_changes = [final_snapshot_identifier, password]
  }

  tags = { Name = "${local.name_prefix}-db" }
}
