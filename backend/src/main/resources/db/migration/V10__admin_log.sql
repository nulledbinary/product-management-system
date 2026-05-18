-- Administrative activity log. Append-only audit trail surfaced only in the
-- Admin section of the app (GET /api/admin/logs, ADM_USER right). Records
-- user lifecycle (create / delete / promote / demote / activate / deactivate)
-- and product lifecycle (create / update / status change) with the acting
-- operator's identity.
--
-- No reject_hard_delete trigger is attached here on purpose: the V3 guards
-- only cover product / "priceHist" / "user". This table is append-only by
-- application convention; it is never deleted from in code.

SET search_path = hopedb, public;

CREATE TABLE admin_log (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  actor_id    VARCHAR(64),
  actor_name  VARCHAR(120),
  actor_email VARCHAR(320),
  action      VARCHAR(40)  NOT NULL,
  target      VARCHAR(160),
  detail      VARCHAR(500)
);

CREATE INDEX idx_admin_log_at ON admin_log (at DESC);
