--liquibase formatted sql

--changeset saamp:004-trading-reconciliation
CREATE TABLE trading_reconciliation_run (
  id            BIGSERIAL PRIMARY KEY,
  started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at  TIMESTAMPTZ NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  message       VARCHAR(256) NULL,
  CONSTRAINT ck_trading_reconciliation_status CHECK (status IN ('RUNNING','MATCH','MISMATCH','ERROR'))
);

CREATE TABLE trading_reconciliation_difference (
  id                BIGSERIAL PRIMARY KEY,
  run_id            BIGINT NOT NULL REFERENCES trading_reconciliation_run(id) ON DELETE RESTRICT,
  asset             VARCHAR(8) NOT NULL,
  internal_quantity NUMERIC(20,6) NOT NULL,
  provider_quantity NUMERIC(20,6) NOT NULL,
  delta             NUMERIC(20,6) NOT NULL,
  CONSTRAINT ck_trading_reconciliation_asset CHECK (asset IN ('EUR','USD','XAU','XAG','XPT','XPD'))
);
CREATE INDEX idx_trading_reconciliation_run_started ON trading_reconciliation_run(started_at DESC);

--rollback DROP TABLE IF EXISTS trading_reconciliation_difference, trading_reconciliation_run CASCADE;
