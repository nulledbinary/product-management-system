-- Widen all audit `stamp` columns from VARCHAR(60) -> VARCHAR(120).
--
-- Root cause of the Google/Auth0 login outage:
--   StampHelper.make(action, userId) builds "<ACTION> <userId> yyyy-MM-dd HH:mm".
--   For an Auth0/Google identity the userId is the provider `sub`
--   (e.g. "google-oauth2|117482938475610293847", ~35 chars), so the stamp
--   is ~63 chars and overflowed user.stamp VARCHAR(60), throwing
--   `PSQLException: value too long for type character varying(60)` from
--   ProvisioningService.provisionOrLoad on first sign-in. Seeded/manual
--   users have short numeric ids, so their stamp fit and they could log in.
--
-- Same overflow would later hit product/priceHist/module/rights stamps once
-- a Google-provisioned user starts writing app data, so every stamp column
-- is widened, not just user.stamp.
--
-- Increasing a varchar length in PostgreSQL (>= 9.2) is a catalog-only
-- change: no table rewrite, only a brief metadata lock. Safe in prod.

SET search_path = hopedb, public;

ALTER TABLE hopedb."user"              ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb.module              ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb.user_module         ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb.rights              ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb."UserModule_Rights" ALTER COLUMN "Stamp" TYPE VARCHAR(120);
ALTER TABLE hopedb.product             ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb."priceHist"         ALTER COLUMN stamp   TYPE VARCHAR(120);
