-- Defence-in-depth: reject hard deletes; protect SUPERADMIN rows; report views.

SET search_path = hopedb, public;

-- ── No hard deletes ────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION reject_hard_delete() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'Hard delete is not permitted on %, use soft delete instead', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_no_delete_product
  BEFORE DELETE ON product
  FOR EACH ROW EXECUTE FUNCTION reject_hard_delete();
CREATE TRIGGER trg_no_delete_pricehist
  BEFORE DELETE ON "priceHist"
  FOR EACH ROW EXECUTE FUNCTION reject_hard_delete();
CREATE TRIGGER trg_no_delete_user
  BEFORE DELETE ON "user"
  FOR EACH ROW EXECUTE FUNCTION reject_hard_delete();

-- ── SUPERADMIN protection ─────────────────────────────────────────────
-- The Spring Boot repository sets hopepms.caller_userid via set_config()
-- in the same transaction as the UPDATE. The trigger then verifies that
-- the caller is a SUPERADMIN whenever the target row is one.
CREATE OR REPLACE FUNCTION enforce_superadmin_protection() RETURNS TRIGGER AS $$
DECLARE
  caller_type TEXT;
  caller_id   TEXT := current_setting('hopepms.caller_userid', true);
BEGIN
  IF OLD.user_type = 'SUPERADMIN' THEN
    IF caller_id IS NULL OR caller_id = '' THEN
      RAISE EXCEPTION 'SUPERADMIN row protected: caller identity not set';
    END IF;
    SELECT user_type INTO caller_type FROM "user" WHERE "userId" = caller_id;
    IF caller_type IS DISTINCT FROM 'SUPERADMIN' THEN
      RAISE EXCEPTION 'SUPERADMIN accounts cannot be modified by %', caller_type;
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_superadmin
  BEFORE UPDATE ON "user"
  FOR EACH ROW EXECUTE FUNCTION enforce_superadmin_protection();

-- ── Report views ──────────────────────────────────────────────────────
CREATE OR REPLACE VIEW v_product_current_price AS
SELECT p."prodCode", p.description, p.unit, ph."unitPrice", ph."effDate",
       p.record_status, p.stamp
  FROM product p
  JOIN LATERAL (
    SELECT "unitPrice", "effDate" FROM "priceHist"
     WHERE "prodCode" = p."prodCode"
     ORDER BY "effDate" DESC LIMIT 1
  ) ph ON TRUE;

CREATE OR REPLACE VIEW v_top_selling AS
SELECT p."prodCode", p.description,
       SUM(sd.quantity)::DECIMAL(14,2) AS "totalQty"
  FROM product p
  JOIN "salesDetail" sd ON sd."prodCode" = p."prodCode"
 WHERE p.record_status = 'ACTIVE'
 GROUP BY p."prodCode", p.description
 ORDER BY "totalQty" DESC;
