-- SUPERADMIN seed (Section 3.4 of the project guide).
-- Pre-populates a placeholder user row that is reconciled with the actual
-- Auth0 sub when jcesperanza@neu.edu.ph first signs in (see provisioning).
--
-- The Auth0 sub is unknown at migration time, so we use the email as a stable
-- key and accept that the first sign-in event will update "userId" to the
-- actual Auth0 sub via the upsert in ProvisioningService.
--
-- IMPORTANT: this row is the *only* path to bootstrap a SUPERADMIN. The UI
-- never creates SUPERADMIN accounts.

SET search_path = hopedb, public;

INSERT INTO "user" (
  "userId", username, "firstName", "lastName", email,
  user_type, record_status, stamp
)
VALUES (
  'seed-superadmin',
  'Jerry',
  'Jeremias',
  'Esperanza',
  'jcesperanza@neu.edu.ph',
  'SUPERADMIN',
  'ACTIVE',
  'SEEDED via Flyway'
)
ON CONFLICT (email) DO NOTHING;

-- Module grants — all modules ON.
INSERT INTO user_module (userid, "Module_ID", rights_value, record_status, stamp)
SELECT u."userId", m."Module_ID", 1, 'ACTIVE', 'SEEDED'
  FROM "user" u CROSS JOIN module m
 WHERE u.email = 'jcesperanza@neu.edu.ph'
ON CONFLICT (userid, "Module_ID") DO UPDATE SET rights_value = 1;

-- Right grants — every right held.
INSERT INTO "UserModule_Rights" (userid, "Right_ID", "Right_value", "Record_status", "Stamp")
SELECT u."userId", r."Right_ID", 1, 'ACTIVE', 'SEEDED'
  FROM "user" u CROSS JOIN rights r
 WHERE u.email = 'jcesperanza@neu.edu.ph'
ON CONFLICT (userid, "Right_ID") DO UPDATE SET "Right_value" = 1;
