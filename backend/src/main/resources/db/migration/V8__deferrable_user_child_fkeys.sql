-- Make the two "user" child foreign keys DEFERRABLE INITIALLY DEFERRED.
--
-- Root cause of the Google/Auth0 login 500 that surfaced AFTER the V7 stamp
-- fix unblocked provisioning:
--   On first Google sign-in for an email pre-seeded with a placeholder
--   userId (V5 SUPERADMIN seed pattern), ProvisioningService.rebindUserId()
--   re-keys the account onto the real Auth0 sub. It updates the CHILD rows
--   before the parent:
--       UPDATE "UserModule_Rights" SET userid   = <newSub> WHERE userid   = <seed>
--       UPDATE  user_module        SET userid   = <newSub> WHERE userid   = <seed>
--       UPDATE "user"              SET "userId" = <newSub> WHERE "userId" = <seed>
--   user_module.userid and "UserModule_Rights".userid both REFERENCE
--   "user"("userId") with the inline, default NOT DEFERRABLE foreign key
--   (see V2). Repointing a child to <newSub> before the parent "user" row
--   exists under <newSub> raised, per statement:
--       ERROR: insert or update on table "UserModule_Rights" violates
--       foreign key constraint "UserModule_Rights_userid_fkey"
--   provisionOrLoad threw, its @Transactional rolled back, and the OAuth
--   callback returned 500 — every email-seeded account was locked out of
--   Google sign-in.
--
-- provisionOrLoad is @Transactional, so making these FKs DEFERRABLE
-- INITIALLY DEFERRED moves the integrity check to COMMIT, by which point
-- the parent and both child tables are all on <newSub> and consistent.
-- This needs no Java change and is correct for every seed->real-sub
-- reconcile, not just one user.
--
-- ALTER CONSTRAINT ... DEFERRABLE is a catalog-only change (brief metadata
-- lock, no table rewrite). It is idempotent: re-applying it to an
-- already-deferrable constraint is a no-op, so this migration is safe even
-- though prod was hot-patched with these identical ALTERs before it shipped.

SET search_path = hopedb, public;

ALTER TABLE hopedb."UserModule_Rights"
  ALTER CONSTRAINT "UserModule_Rights_userid_fkey" DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE hopedb.user_module
  ALTER CONSTRAINT user_module_userid_fkey DEFERRABLE INITIALLY DEFERRED;
