-- Root cause of the user "Delete" failure ("Unexpected Error Occured"):
--
--   V3 installed trg_no_delete_user BEFORE DELETE ON "user", whose function
--   reject_hard_delete() unconditionally RAISEs. AdminUsersController.eradicate
--   off-boards an account with a real
--       DELETE FROM hopedb."user" WHERE "userId" = :uid
--   so every off-boarding hit that RAISE. Postgres surfaced it as a
--   DataAccessException which the API returned as a 5xx -- to the operator,
--   an opaque "Unexpected Error". The delete was never possible at all.
--
-- Fix: the no-hard-delete guard is correct for product / "priceHist" (those
-- are only ever soft-deleted via record_status) but must yield for the
-- deliberate SUPERADMIN off-boarding flow. The eradicate transaction now
-- opts in explicitly with
--     set_config('hopepms.allow_user_delete','on', true)
-- (transaction-local, mirrors the existing hopepms.caller_userid pattern).
-- The trigger lets a "user" row through only when that flag is set; product
-- and price history are still hard-delete-proof.
--
-- CREATE OR REPLACE is idempotent and changes only the function body, so it
-- is safe on the hot-patched prod DB and on a clean rebuild alike.

SET search_path = hopedb, public;

CREATE OR REPLACE FUNCTION reject_hard_delete() RETURNS TRIGGER AS $$
BEGIN
  IF TG_TABLE_NAME = 'user'
     AND current_setting('hopepms.allow_user_delete', true) = 'on' THEN
    RETURN OLD;  -- BEFORE DELETE: returning OLD lets the delete proceed
  END IF;
  RAISE EXCEPTION 'Hard delete is not permitted on %, use soft delete instead', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;
