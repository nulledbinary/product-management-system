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
--   Same overflow would later hit product/priceHist/module/rights stamps
--   once a Google-provisioned user starts writing app data, so every stamp
--   column is widened, not just user.stamp.
--
-- View dependency:
--   v_product_current_price (V3__defense_triggers_and_views.sql) selects
--   product.stamp, so PostgreSQL refuses `ALTER COLUMN product.stamp TYPE`
--   while the view exists ("cannot alter type of a column used by a view
--   or rule"). The view is dropped, the columns widened, then the view is
--   recreated verbatim. No other view depends on a stamp column
--   (v_top_selling does not select stamp).
--
-- Increasing a varchar length in PostgreSQL (>= 9.2) is a catalog-only
-- change: no table rewrite, only a brief metadata lock. Safe in prod.

SET search_path = hopedb, public;

DROP VIEW IF EXISTS v_product_current_price;

ALTER TABLE hopedb."user"              ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb.module              ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb.user_module         ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb.rights              ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb."UserModule_Rights" ALTER COLUMN "Stamp" TYPE VARCHAR(120);
ALTER TABLE hopedb.product             ALTER COLUMN stamp   TYPE VARCHAR(120);
ALTER TABLE hopedb."priceHist"         ALTER COLUMN stamp   TYPE VARCHAR(120);

-- Recreate verbatim from V3__defense_triggers_and_views.sql (lines 49-57).
CREATE OR REPLACE VIEW v_product_current_price AS
SELECT p."prodCode", p.description, p.unit, ph."unitPrice", ph."effDate",
       p.record_status, p.stamp
  FROM product p
  JOIN LATERAL (
    SELECT "unitPrice", "effDate" FROM "priceHist"
     WHERE "prodCode" = p."prodCode"
     ORDER BY "effDate" DESC LIMIT 1
  ) ph ON TRUE;
