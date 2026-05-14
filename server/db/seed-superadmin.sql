-- ─── SUPERADMIN seed (HopePMS docx §3.4) ────────────────────────────────
-- Replace 'auth0|REPLACE_ME' with the actual Auth0 sub for jcesperanza@neu.edu.ph
-- after creating the user in Auth0. The application UI must never create or
-- mutate SUPERADMIN rows.

SET search_path = hopedb, public;

INSERT INTO "user" ("userId", username, "firstName", "lastName", email, user_type, record_status, stamp)
VALUES
  ('auth0|REPLACE_ME', 'Jerry', 'Jeremias', 'Esperanza',
   'jcesperanza@neu.edu.ph', 'SUPERADMIN', 'ACTIVE',
   'SEEDED system 2026-05-14 00:00');

-- Full module access
INSERT INTO user_module (userid, "Module_ID", rights_value, record_status, stamp) VALUES
  ('auth0|REPLACE_ME', 'Prod_Mod',   1, 'ACTIVE', 'SEED'),
  ('auth0|REPLACE_ME', 'Report_Mod', 1, 'ACTIVE', 'SEED'),
  ('auth0|REPLACE_ME', 'Adm_Mod',    1, 'ACTIVE', 'SEED');

-- All 6 rights
INSERT INTO "UserModule_Rights" (userid, "Right_ID", "Right_value", "Record_status", "Stamp") VALUES
  ('auth0|REPLACE_ME', 'PRD_ADD',  1, 'ACTIVE', 'SEED'),
  ('auth0|REPLACE_ME', 'PRD_EDIT', 1, 'ACTIVE', 'SEED'),
  ('auth0|REPLACE_ME', 'PRD_DEL',  1, 'ACTIVE', 'SEED'),
  ('auth0|REPLACE_ME', 'REP_001',  1, 'ACTIVE', 'SEED'),
  ('auth0|REPLACE_ME', 'REP_002',  1, 'ACTIVE', 'SEED'),
  ('auth0|REPLACE_ME', 'ADM_USER', 1, 'ACTIVE', 'SEED');
