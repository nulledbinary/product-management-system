-- Rights Management tables. Maps Auth0 sub → user_type + module/right grants.

SET search_path = hopedb, public;

CREATE TABLE "user" (
  "userId"       VARCHAR(64)  PRIMARY KEY,
  username       VARCHAR(32)  NOT NULL UNIQUE,
  "firstName"    VARCHAR(50)  NOT NULL,
  "lastName"     VARCHAR(50)  NOT NULL,
  email          VARCHAR(320) NOT NULL UNIQUE,
  user_type      VARCHAR(10)  NOT NULL DEFAULT 'USER'
                   CHECK (user_type IN ('SUPERADMIN','ADMIN','USER')),
  record_status  VARCHAR(10)  NOT NULL DEFAULT 'INACTIVE'
                   CHECK (record_status IN ('ACTIVE','INACTIVE')),
  stamp          VARCHAR(60),
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE module (
  "Module_ID"   VARCHAR(20) PRIMARY KEY,
  description   VARCHAR(80) NOT NULL,
  record_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  stamp         VARCHAR(60)
);

CREATE TABLE user_module (
  userid        VARCHAR(64) NOT NULL REFERENCES "user"("userId"),
  "Module_ID"   VARCHAR(20) NOT NULL REFERENCES module("Module_ID"),
  rights_value  SMALLINT    NOT NULL DEFAULT 0 CHECK (rights_value IN (0,1)),
  record_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  stamp         VARCHAR(60),
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
  "Right_value"   SMALLINT    NOT NULL DEFAULT 0 CHECK ("Right_value" IN (0,1)),
  "Record_status" VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  "Stamp"         VARCHAR(60),
  PRIMARY KEY (userid, "Right_ID")
);
CREATE INDEX idx_umr_user ON "UserModule_Rights" (userid);
