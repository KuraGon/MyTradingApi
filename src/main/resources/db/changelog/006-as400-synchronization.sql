--liquibase formatted sql

--changeset saamp:006-as400-synchronization
ALTER TABLE trading_account
  ADD COLUMN as400_ste VARCHAR(8) NULL,
  ADD COLUMN as400_nucli_commercial INTEGER NULL,
  ADD COLUMN as400_nucli_trading INTEGER NULL,
  ADD CONSTRAINT ck_trading_account_as400_distinct_nucli CHECK (as400_nucli_commercial IS NULL OR as400_nucli_trading IS NULL OR as400_nucli_commercial <> as400_nucli_trading);

CREATE TABLE trading_as400_sync_outbox (
  id BIGSERIAL PRIMARY KEY,
  order_id BIGINT NOT NULL REFERENCES trading_order(id) ON DELETE RESTRICT,
  target VARCHAR(24) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  synced_at TIMESTAMPTZ NULL,
  last_error VARCHAR(512) NULL,
  CONSTRAINT uq_trading_as400_sync_order_target UNIQUE (order_id, target),
  CONSTRAINT ck_trading_as400_sync_target CHECK (target IN ('WEIGHT_ACCOUNT','ACCOUNTING')),
  CONSTRAINT ck_trading_as400_sync_status CHECK (status IN ('PENDING','RETRY','BLOCKED','SYNCED'))
);
CREATE INDEX idx_trading_as400_sync_due ON trading_as400_sync_outbox(next_attempt_at, id) WHERE status IN ('PENDING','RETRY','BLOCKED');

--rollback DROP TABLE IF EXISTS trading_as400_sync_outbox;
