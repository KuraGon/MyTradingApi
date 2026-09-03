--liquibase formatted sql

--changeset saamp:005-pmxconnect-corrections
CREATE TABLE trading_provider_account (
  id            BIGSERIAL PRIMARY KEY,
  provider      VARCHAR(24)  NOT NULL,
  account_code  VARCHAR(32)  NOT NULL,
  client_id     VARCHAR(64)  NULL,
  first_seen_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_trading_provider_account UNIQUE (provider, account_code)
);
CREATE INDEX idx_trading_provider_account_seen ON trading_provider_account(provider, last_seen_at DESC);

ALTER TABLE trading_reconciliation_run
  ADD COLUMN provider_account_code VARCHAR(32) NULL;

--rollback ALTER TABLE trading_reconciliation_run DROP COLUMN IF EXISTS provider_account_code;
--rollback DROP TABLE IF EXISTS trading_provider_account;
