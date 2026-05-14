# ─── Database credentials ────────────────────────────────────────────────
resource "aws_secretsmanager_secret" "db" {
  name        = "${local.name_prefix}/db"
  description = "HopePMS PostgreSQL master credentials"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    DB_USER     = var.db_username
    DB_PASSWORD = random_password.db_master.result
    DB_URL      = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${var.db_name}"
  })
}

# ─── Auth0 (placeholders — update after Auth0 application is created) ────
resource "aws_secretsmanager_secret" "auth0" {
  name        = "${local.name_prefix}/auth0"
  description = "HopePMS Auth0 credentials. UPDATE AUTH0_CLIENT_SECRET after creating the Auth0 application."
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "auth0" {
  secret_id = aws_secretsmanager_secret.auth0.id
  secret_string = jsonencode({
    AUTH0_CLIENT_SECRET = var.auth0_client_secret_placeholder
  })

  lifecycle {
    # Once you rotate this with the real Auth0 secret, Terraform should NOT
    # overwrite it on the next apply.
    ignore_changes = [secret_string]
  }
}
