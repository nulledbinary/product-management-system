-- Root cause of the two prod "The database rejected this request" / opaque
-- failures on SUPERADMIN-row writes (confirmed from CloudWatch
-- /ecs/hopepms-backend, 2026-05-18):
--
--   1) ERROR: relation "user" does not exist
--      enforce_superadmin_protection() (V3) queries an UNQUALIFIED "user":
--          SELECT user_type INTO caller_type FROM "user" WHERE ...
--      The `SET search_path = hopedb, public` at the top of V3 only applies
--      while the migration script runs -- it does NOT travel with the
--      function. At trigger runtime the function executes under the JDBC
--      session's search_path, which never includes `hopedb` (every app
--      statement is schema-qualified as hopedb."user", and Flyway's
--      default-schema only scopes its own history table, not the runtime
--      datasource). So the unqualified "user" fails to resolve and any
--      UPDATE that lands on a SUPERADMIN row (demote / promote a SUPERADMIN,
--      or the eradicate stamp-scrub touching a SUPERADMIN row) is rejected.
--
-- Fix: redefine the function with the table reference fully schema-qualified
-- (hopedb."user") AND pin a function-local `SET search_path = hopedb, public`
-- on the definition itself. The pinned search_path is the recommended
-- hardening for a security-relevant trigger function: it makes name
-- resolution deterministic regardless of the caller's session state and
-- closes the search_path-hijack vector. Behaviour is otherwise identical to
-- V3 -- same checks, same RAISE messages.
--
-- CREATE OR REPLACE only swaps the function body/attributes; the existing
-- trg_protect_superadmin trigger keeps pointing at it. Idempotent and safe
-- on the hot-patched prod DB and on a clean rebuild alike.

SET search_path = hopedb, public;

CREATE OR REPLACE FUNCTION enforce_superadmin_protection()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SET search_path = hopedb, public
AS $$
DECLARE
  caller_type TEXT;
  caller_id   TEXT := current_setting('hopepms.caller_userid', true);
BEGIN
  IF OLD.user_type = 'SUPERADMIN' THEN
    IF caller_id IS NULL OR caller_id = '' THEN
      RAISE EXCEPTION 'SUPERADMIN row protected: caller identity not set';
    END IF;
    SELECT user_type INTO caller_type
      FROM hopedb."user"
     WHERE "userId" = caller_id;
    IF caller_type IS DISTINCT FROM 'SUPERADMIN' THEN
      RAISE EXCEPTION 'SUPERADMIN accounts cannot be modified by %', caller_type;
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
