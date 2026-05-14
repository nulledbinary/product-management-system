-- HopeDB core schema (employee/department/job/jobHistory/customer/sales/...).
-- Identifiers are double-quoted camelCase to match the original HopeDB script.
-- Flyway target schema: hopedb (configured in application.yml).

SET search_path = hopedb, public;

CREATE TABLE department (
  "deptCode" VARCHAR(3) PRIMARY KEY,
  "deptName" VARCHAR(20)
);

CREATE TABLE employee (
  "empNo"     VARCHAR(5) PRIMARY KEY,
  "lastName"  VARCHAR(15),
  "firstName" VARCHAR(15),
  gender      CHAR(1) CONSTRAINT gender_ck CHECK (gender IN ('M','F')),
  "birthDate" DATE,
  "hireDate"  DATE,
  "sepDate"   DATE
);

CREATE TABLE job (
  "jobCode" VARCHAR(4) PRIMARY KEY,
  "jobDesc" VARCHAR(20)
);

CREATE TABLE "jobHistory" (
  "empNo"    VARCHAR(5) NOT NULL REFERENCES employee("empNo"),
  "jobCode"  VARCHAR(4) NOT NULL REFERENCES job("jobCode"),
  "effDate"  DATE NOT NULL,
  salary     DECIMAL(10,2) CONSTRAINT salary_ck CHECK (salary >= 0.0),
  "deptCode" VARCHAR(4),
  PRIMARY KEY ("empNo", "jobCode", "effDate"),
  FOREIGN KEY ("deptCode") REFERENCES department("deptCode")
);

CREATE TABLE customer (
  "custNo"   VARCHAR(5) PRIMARY KEY,
  "custName" VARCHAR(50),
  address    VARCHAR(80),
  "payTerm"  VARCHAR(3) CONSTRAINT pay_ck CHECK ("payTerm" IN ('COD','30D','45D'))
);

CREATE TABLE sales (
  "transNo"   VARCHAR(8) PRIMARY KEY,
  "salesDate" DATE,
  "custNo"    VARCHAR(5) REFERENCES customer("custNo"),
  "empNo"     VARCHAR(5) REFERENCES employee("empNo")
);

CREATE TABLE product (
  "prodCode"    VARCHAR(6) PRIMARY KEY,
  description   VARCHAR(30) NOT NULL,
  unit          VARCHAR(3)  NOT NULL
                  CONSTRAINT unit_ck CHECK (unit IN ('pc','ea','mtr','pkg','ltr')),
  record_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                  CHECK (record_status IN ('ACTIVE','INACTIVE')),
  stamp         VARCHAR(60)
);
CREATE INDEX idx_product_status ON product (record_status);

CREATE TABLE "salesDetail" (
  "transNo"  VARCHAR(8)  NOT NULL REFERENCES sales("transNo"),
  "prodCode" VARCHAR(6)  NOT NULL REFERENCES product("prodCode"),
  quantity   DECIMAL(10,2) CONSTRAINT quantity_ck CHECK (quantity >= 0.0),
  PRIMARY KEY ("transNo", "prodCode")
);
CREATE INDEX idx_sales_prod ON "salesDetail" ("prodCode");

CREATE TABLE payment (
  "orNo"    VARCHAR(8) PRIMARY KEY,
  "payDate" DATE,
  amount    DECIMAL(10,2),
  "transNo" VARCHAR(8) REFERENCES sales("transNo")
);

CREATE TABLE "priceHist" (
  "effDate"   DATE NOT NULL,
  "prodCode"  VARCHAR(6) NOT NULL REFERENCES product("prodCode"),
  "unitPrice" DECIMAL(10,2) NOT NULL
                CONSTRAINT unitP_ck CHECK ("unitPrice" > 0),
  stamp       VARCHAR(60),
  PRIMARY KEY ("effDate", "prodCode")
);
CREATE INDEX idx_pricehist_prod ON "priceHist" ("prodCode");
