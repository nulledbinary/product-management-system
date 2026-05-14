-- ─────────────────────────────────────────────────────────────────────────
--  HopeDB schema for HopePMS (Astro & AWS edition)
--
--  This file is the union of:
--    • the original HopeDB tables from "HopeDB (3).sql"
--      (employee, department, job, jobHistory, customer, sales,
--       salesDetail, product, payment, priceHist), and
--    • the HopePMS additions specified in PROJECT_PLAN.md / HopePMS.docx
--      (record_status + stamp columns, the Rights Management tables,
--       the soft-delete + SUPERADMIN-protection triggers, the report views).
--
--  Target engine: Amazon RDS PostgreSQL 14+.
--  Identifiers are double-quoted camelCase to preserve the exact column
--  names used by the original HopeDB and referenced by the Lambda handlers.
-- ─────────────────────────────────────────────────────────────────────────

DROP SCHEMA IF EXISTS hopedb CASCADE;
CREATE SCHEMA hopedb;
SET search_path = hopedb, public;

-- ═════════════════════════════════════════════════════════════════════════
--  SECTION 1 — Original HopeDB tables (with the HopePMS-spec additions)
-- ═════════════════════════════════════════════════════════════════════════

-- ─── department ────────────────────────────────────────────────────────
CREATE TABLE department (
  "deptCode"  VARCHAR(3)  PRIMARY KEY,
  "deptName"  VARCHAR(20)
);

-- ─── employee ──────────────────────────────────────────────────────────
CREATE TABLE employee (
  "empNo"     VARCHAR(5)  PRIMARY KEY,
  "lastName"  VARCHAR(15),
  "firstName" VARCHAR(15),
  gender      CHAR(1)     CONSTRAINT gender_ck CHECK (gender IN ('M','F')),
  "birthDate" DATE,
  "hireDate"  DATE,
  "sepDate"   DATE
);

-- ─── job ───────────────────────────────────────────────────────────────
CREATE TABLE job (
  "jobCode"   VARCHAR(4)  PRIMARY KEY,
  "jobDesc"   VARCHAR(20)
);

-- ─── jobHistory ────────────────────────────────────────────────────────
CREATE TABLE "jobHistory" (
  "empNo"    VARCHAR(5)   NOT NULL REFERENCES employee("empNo"),
  "jobCode"  VARCHAR(4)   NOT NULL REFERENCES job("jobCode"),
  "effDate"  DATE         NOT NULL,
  salary     DECIMAL(10,2) CONSTRAINT salary_ck CHECK (salary >= 0.0),
  "deptCode" VARCHAR(4),
  PRIMARY KEY ("empNo", "jobCode", "effDate"),
  FOREIGN KEY ("deptCode") REFERENCES department("deptCode")
);

-- ─── customer ──────────────────────────────────────────────────────────
CREATE TABLE customer (
  "custNo"    VARCHAR(5)  PRIMARY KEY,
  "custName"  VARCHAR(50),
  address     VARCHAR(80),
  "payTerm"   VARCHAR(3)  CONSTRAINT pay_ck CHECK ("payTerm" IN ('COD','30D','45D'))
);

-- ─── sales ─────────────────────────────────────────────────────────────
CREATE TABLE sales (
  "transNo"   VARCHAR(8)  PRIMARY KEY,
  "salesDate" DATE,
  "custNo"    VARCHAR(5)  REFERENCES customer("custNo"),
  "empNo"     VARCHAR(5)  REFERENCES employee("empNo")
);

-- ─── product (HopePMS-modified: + record_status, + stamp) ──────────────
CREATE TABLE product (
  "prodCode"      VARCHAR(6)  PRIMARY KEY,
  description     VARCHAR(30) NOT NULL,
  unit            VARCHAR(3)  NOT NULL
                   CONSTRAINT unit_ck CHECK (unit IN ('pc','ea','mtr','pkg','ltr')),
  record_status   VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (record_status IN ('ACTIVE','INACTIVE')),
  stamp           VARCHAR(60)
);

CREATE INDEX idx_product_status ON product (record_status);

-- ─── salesDetail (DECIMAL quantity to match HopeDB original) ──────────
CREATE TABLE "salesDetail" (
  "transNo"   VARCHAR(8)   NOT NULL REFERENCES sales("transNo"),
  "prodCode"  VARCHAR(6)   NOT NULL REFERENCES product("prodCode"),
  quantity    DECIMAL(10,2) CONSTRAINT quantity_ck CHECK (quantity >= 0.0),
  PRIMARY KEY ("transNo", "prodCode")
);

CREATE INDEX idx_sales_prod ON "salesDetail" ("prodCode");

-- ─── payment ───────────────────────────────────────────────────────────
CREATE TABLE payment (
  "orNo"     VARCHAR(8)  PRIMARY KEY,
  "payDate"  DATE,
  amount     DECIMAL(10,2),
  "transNo"  VARCHAR(8)  REFERENCES sales("transNo")
);

-- ─── priceHist (HopePMS-modified: + stamp) ─────────────────────────────
CREATE TABLE "priceHist" (
  "effDate"   DATE          NOT NULL,
  "prodCode"  VARCHAR(6)    NOT NULL REFERENCES product("prodCode"),
  "unitPrice" DECIMAL(10,2) NOT NULL
                CONSTRAINT unitP_ck CHECK ("unitPrice" > 0),
  stamp       VARCHAR(60),
  PRIMARY KEY ("effDate", "prodCode")
);

CREATE INDEX idx_pricehist_prod ON "priceHist" ("prodCode");

-- ═════════════════════════════════════════════════════════════════════════
--  SECTION 2 — Rights Management tables (HopePMS docx §3)
-- ═════════════════════════════════════════════════════════════════════════

CREATE TABLE "user" (
  "userId"       VARCHAR(64) PRIMARY KEY,       -- Auth0 sub
  username       VARCHAR(32) NOT NULL UNIQUE,
  "firstName"    VARCHAR(50) NOT NULL,
  "lastName"     VARCHAR(50) NOT NULL,
  email          VARCHAR(320) NOT NULL UNIQUE,
  user_type      VARCHAR(10) NOT NULL DEFAULT 'USER'
                   CHECK (user_type IN ('SUPERADMIN','ADMIN','USER')),
  record_status  VARCHAR(10) NOT NULL DEFAULT 'INACTIVE'
                   CHECK (record_status IN ('ACTIVE','INACTIVE')),
  stamp          VARCHAR(60),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE module (
  "Module_ID"   VARCHAR(20) PRIMARY KEY,
  description   VARCHAR(80) NOT NULL,
  record_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  stamp         VARCHAR(60)
);

CREATE TABLE user_module (
  userid         VARCHAR(64) NOT NULL REFERENCES "user"("userId"),
  "Module_ID"    VARCHAR(20) NOT NULL REFERENCES module("Module_ID"),
  rights_value   SMALLINT NOT NULL DEFAULT 0 CHECK (rights_value IN (0,1)),
  record_status  VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  stamp          VARCHAR(60),
  PRIMARY KEY (userid, "Module_ID")
);

CREATE TABLE rights (
  "Right_ID"    VARCHAR(20) PRIMARY KEY,
  description   VARCHAR(80) NOT NULL,
  "Module_ID"   VARCHAR(20) NOT NULL REFERENCES module("Module_ID"),
  record_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  stamp         VARCHAR(60)
);

CREATE TABLE "UserModule_Rights" (
  userid          VARCHAR(64) NOT NULL REFERENCES "user"("userId"),
  "Right_ID"      VARCHAR(20) NOT NULL REFERENCES rights("Right_ID"),
  "Right_value"   SMALLINT NOT NULL DEFAULT 0 CHECK ("Right_value" IN (0,1)),
  "Record_status" VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  "Stamp"         VARCHAR(60),
  PRIMARY KEY (userid, "Right_ID")
);

CREATE INDEX idx_umr_user ON "UserModule_Rights" (userid);

-- ═════════════════════════════════════════════════════════════════════════
--  SECTION 3 — Views (REP_001 / REP_002 read paths)
-- ═════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE VIEW v_product_current_price AS
SELECT
  p."prodCode",
  p.description,
  p.unit,
  ph."unitPrice",
  ph."effDate",
  p.record_status,
  p.stamp
FROM product p
JOIN LATERAL (
  SELECT "unitPrice", "effDate"
  FROM "priceHist"
  WHERE "prodCode" = p."prodCode"
  ORDER BY "effDate" DESC
  LIMIT 1
) ph ON TRUE;

CREATE OR REPLACE VIEW v_top_selling AS
SELECT
  p."prodCode",
  p.description,
  SUM(sd.quantity)::DECIMAL(14,2) AS "totalQty"
FROM product p
JOIN "salesDetail" sd ON sd."prodCode" = p."prodCode"
WHERE p.record_status = 'ACTIVE'
GROUP BY p."prodCode", p.description
ORDER BY "totalQty" DESC;

-- ═════════════════════════════════════════════════════════════════════════
--  SECTION 4 — Defence-in-depth triggers
-- ═════════════════════════════════════════════════════════════════════════

-- 4.1 No hard deletes on protected tables. Belt-and-braces: the application
--     code never issues DELETE; this catches accidents and ad-hoc tools.
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

-- 4.2 SUPERADMIN protection. The Lambda sets `hopepms.caller_userid` in the
--     transaction; the trigger refuses any UPDATE on a SUPERADMIN row unless
--     the caller's own user_type is SUPERADMIN.
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

-- ═════════════════════════════════════════════════════════════════════════
--  SECTION 5 — Modules + Rights catalog (required for app to boot)
-- ═════════════════════════════════════════════════════════════════════════

INSERT INTO module ("Module_ID", description) VALUES
  ('Prod_Mod',   'Product Management'),
  ('Report_Mod', 'Reports'),
  ('Adm_Mod',    'Administration');

INSERT INTO rights ("Right_ID", description, "Module_ID") VALUES
  ('PRD_ADD',  'Add Product',            'Prod_Mod'),
  ('PRD_EDIT', 'Edit Product',           'Prod_Mod'),
  ('PRD_DEL',  'Soft-Delete Product',    'Prod_Mod'),
  ('REP_001',  'Product Listing Report', 'Report_Mod'),
  ('REP_002',  'Top Selling Report',     'Report_Mod'),
  ('ADM_USER', 'Manage Users',           'Adm_Mod');
