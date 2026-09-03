--liquibase formatted sql

--changeset saamp:001-trading-core

CREATE TABLE trading_account (
  id                BIGSERIAL PRIMARY KEY,
  company_id        BIGINT       NOT NULL,
  base_currency     VARCHAR(3)   NOT NULL,
  status            VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
  deal_limit        NUMERIC(18,2) NULL,
  position_limit    NUMERIC(18,2) NULL,
  loss_limit        NUMERIC(18,2) NULL,
  config_version    INTEGER      NOT NULL DEFAULT 1,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_trading_account_currency CHECK (base_currency IN ('EUR','USD')),
  CONSTRAINT ck_trading_account_status CHECK (status IN ('ACTIVE','RISK_RESTRICTED','SUSPENDED','CLOSED'))
);
CREATE UNIQUE INDEX uq_trading_account_company ON trading_account(company_id);

CREATE TABLE trading_order_batch (
  id             BIGSERIAL PRIMARY KEY,
  account_id     BIGINT      NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  status         VARCHAR(24) NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  submitted_at   TIMESTAMPTZ NULL,
  created_by     BIGINT      NOT NULL,
  CONSTRAINT ck_trading_order_batch_status CHECK (status IN ('DRAFT','VALIDATED','SUBMITTED','COMPLETED'))
);

CREATE TABLE trading_order (
  id                       BIGSERIAL PRIMARY KEY,
  account_id               BIGINT        NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  company_id               BIGINT        NOT NULL,
  batch_id                 BIGINT        NULL REFERENCES trading_order_batch(id) ON DELETE RESTRICT,
  asset                    VARCHAR(8)    NOT NULL,
  pair                     VARCHAR(8)    NOT NULL,
  side                     VARCHAR(4)    NOT NULL,
  order_type               VARCHAR(8)    NOT NULL DEFAULT 'SPOT',
  fixing_date              DATE          NULL,
  fixing_code              VARCHAR(24)   NULL,
  requested_quantity       NUMERIC(20,6) NOT NULL,
  requested_unit           VARCHAR(4)    NOT NULL,
  quantity_oz              NUMERIC(20,6) NOT NULL,
  status                   VARCHAR(24)   NOT NULL,
  indicative_price         NUMERIC(20,6) NULL,
  indicative_client_price_raw NUMERIC(20,12) NULL,
  indicative_client_price  NUMERIC(20,6) NULL,
  market_price             NUMERIC(20,6) NULL,
  client_price_raw         NUMERIC(20,12) NULL,
  client_price             NUMERIC(20,6) NULL,
  spread_applied           NUMERIC(9,6)  NULL,
  spread_config_version    INTEGER       NULL,
  saamp_revenue            NUMERIC(20,6) NULL,
  gross_amount             NUMERIC(20,2) NULL,
  idempotency_key          VARCHAR(64)   NOT NULL,
  cl_ord_id                VARCHAR(40)   NOT NULL,
  stonex_exid              VARCHAR(64)   NULL,
  stonex_error_code        VARCHAR(16)   NULL,
  stonex_error_message     TEXT          NULL,
  resolution_attempts      INTEGER       NOT NULL DEFAULT 0,
  unknown_since            TIMESTAMPTZ   NULL,
  next_resolution_at       TIMESTAMPTZ   NULL,
  manual_review_at         TIMESTAMPTZ   NULL,
  created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  submitted_at             TIMESTAMPTZ   NULL,
  executed_at              TIMESTAMPTZ   NULL,
  CONSTRAINT uq_trading_order_idempotency UNIQUE (idempotency_key),
  CONSTRAINT uq_trading_order_cl_ord_id UNIQUE (cl_ord_id),
  CONSTRAINT ck_trading_order_asset CHECK (asset IN ('XAU','XAG','XPT','XPD')),
  CONSTRAINT ck_trading_order_side CHECK (side IN ('BUY','SELL')),
  CONSTRAINT ck_trading_order_unit CHECK (requested_unit IN ('G','KG','OZ')),
  CONSTRAINT ck_trading_order_type CHECK (order_type IN ('SPOT')),
  CONSTRAINT ck_trading_order_status CHECK (status IN ('DRAFT','VALIDATED','PENDING','PENDING_UNKNOWN','FILLED','REJECTED','EXPIRED','FAILED')),
  CONSTRAINT ck_trading_order_qty_positive CHECK (requested_quantity > 0 AND quantity_oz > 0)
);
CREATE INDEX idx_trading_order_account_created ON trading_order(account_id, created_at DESC);
CREATE INDEX idx_trading_order_status ON trading_order(status);
CREATE INDEX idx_trading_order_resolution_due ON trading_order(next_resolution_at) WHERE status = 'PENDING_UNKNOWN';

CREATE TABLE trading_balance (
  account_id   BIGINT        NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  asset        VARCHAR(8)    NOT NULL,
  quantity     NUMERIC(20,6) NOT NULL DEFAULT 0,
  updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  PRIMARY KEY (account_id, asset),
  CONSTRAINT ck_trading_balance_asset CHECK (asset IN ('EUR','USD','XAU','XAG','XPT','XPD'))
);

-- Liquibase formatted SQL decoupe les instructions sur les ';'.
-- Le corps PL/pgSQL est donc fourni comme chaine SQL quotee afin que
-- les ';' internes ne soient pas interpretes comme des fins d'instruction.
CREATE OR REPLACE FUNCTION check_trading_balance_currency_non_negative()
RETURNS TRIGGER AS '
BEGIN
  IF NEW.asset IN (''EUR'',''USD'') AND NEW.quantity < 0 THEN
    RAISE EXCEPTION ''Solde devise negatif interdit (compte %, actif %, valeur %)'',
      NEW.account_id, NEW.asset, NEW.quantity;
  END IF;
  RETURN NEW;
END;
' LANGUAGE plpgsql;

CREATE TRIGGER trg_trading_balance_currency_non_negative
  BEFORE INSERT OR UPDATE ON trading_balance
  FOR EACH ROW EXECUTE FUNCTION check_trading_balance_currency_non_negative();

CREATE TABLE trading_ledger_entry (
  id             BIGSERIAL PRIMARY KEY,
  account_id     BIGINT        NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  asset          VARCHAR(8)    NOT NULL,
  delta          NUMERIC(20,6) NOT NULL,
  entry_type     VARCHAR(24)   NOT NULL,
  order_id       BIGINT        NULL REFERENCES trading_order(id) ON DELETE RESTRICT,
  transfer_ref   VARCHAR(64)   NULL,
  balance_after  NUMERIC(20,6) NOT NULL,
  created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  created_by     VARCHAR(64)   NOT NULL,
  CONSTRAINT ck_trading_ledger_asset CHECK (asset IN ('EUR','USD','XAU','XAG','XPT','XPD')),
  CONSTRAINT ck_trading_ledger_type CHECK (entry_type IN ('TRANSFER_IN','TRANSFER_OUT','TRADE','FEE','ADJUSTMENT')),
  CONSTRAINT ck_trading_ledger_nonzero CHECK (delta <> 0)
);
CREATE INDEX idx_trading_ledger_account ON trading_ledger_entry(account_id, created_at DESC);
CREATE INDEX idx_trading_ledger_order ON trading_ledger_entry(order_id);

CREATE OR REPLACE FUNCTION forbid_trading_ledger_mutation()
RETURNS TRIGGER AS '
BEGIN
  RAISE EXCEPTION ''trading_ledger_entry est append-only; utiliser une ecriture ADJUSTMENT'';
END;
' LANGUAGE plpgsql;

CREATE TRIGGER trg_trading_ledger_append_only
  BEFORE UPDATE OR DELETE ON trading_ledger_entry
  FOR EACH ROW EXECUTE FUNCTION forbid_trading_ledger_mutation();

CREATE TABLE trading_reservation (
  id            BIGSERIAL PRIMARY KEY,
  account_id    BIGINT        NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  asset         VARCHAR(8)    NOT NULL,
  quantity      NUMERIC(20,6) NOT NULL CHECK (quantity > 0),
  order_id      BIGINT        NULL REFERENCES trading_order(id) ON DELETE RESTRICT,
  batch_id      BIGINT        NULL REFERENCES trading_order_batch(id) ON DELETE RESTRICT,
  status        VARCHAR(16)   NOT NULL,
  expires_at    TIMESTAMPTZ   NOT NULL,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_trading_reservation_asset CHECK (asset IN ('EUR','USD','XAU','XAG','XPT','XPD')),
  CONSTRAINT ck_trading_reservation_status CHECK (status IN ('ACTIVE','CONSUMED','RELEASED','EXPIRED'))
);
CREATE INDEX idx_trading_reservation_active ON trading_reservation(account_id, asset) WHERE status = 'ACTIVE';
CREATE INDEX idx_trading_reservation_order ON trading_reservation(order_id);

CREATE TABLE trading_margin_rate (
  id           BIGSERIAL PRIMARY KEY,
  account_id   BIGINT       NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  asset        VARCHAR(8)   NOT NULL,
  rate         NUMERIC(6,4) NOT NULL CHECK (rate >= 0 AND rate <= 1),
  active_from  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  active_to    TIMESTAMPTZ  NULL,
  CONSTRAINT ck_trading_margin_asset CHECK (asset IN ('EUR','USD','XAU','XAG','XPT','XPD')),
  CONSTRAINT ck_trading_margin_window CHECK (active_to IS NULL OR active_to > active_from)
);
CREATE INDEX idx_trading_margin_current ON trading_margin_rate(asset, account_id, active_from DESC);

CREATE TABLE trading_transfer (
  id                BIGSERIAL PRIMARY KEY,
  account_id        BIGINT        NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  asset             VARCHAR(8)    NOT NULL,
  quantity          NUMERIC(20,6) NOT NULL CHECK (quantity > 0),
  direction         VARCHAR(4)    NOT NULL,
  external_ref      VARCHAR(64)   NOT NULL UNIQUE,
  acquisition_price NUMERIC(20,6) NULL,
  status            VARCHAR(16)   NOT NULL,
  received_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  applied_at        TIMESTAMPTZ   NULL,
  rejected_reason   VARCHAR(128)  NULL,
  CONSTRAINT ck_trading_transfer_asset CHECK (asset IN ('EUR','USD','XAU','XAG','XPT','XPD')),
  CONSTRAINT ck_trading_transfer_direction CHECK (direction IN ('IN','OUT')),
  CONSTRAINT ck_trading_transfer_status CHECK (status IN ('RECEIVED','APPLIED','REJECTED'))
);

CREATE TABLE trading_risk_snapshot (
  id                 BIGSERIAL PRIMARY KEY,
  account_id         BIGINT        NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  computed_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  price_as_of        TIMESTAMPTZ   NOT NULL,
  total_funds        NUMERIC(20,2) NOT NULL,
  position_valuation NUMERIC(20,2) NOT NULL,
  net_equity         NUMERIC(20,2) NOT NULL,
  margin_requirement NUMERIC(20,2) NOT NULL,
  free_equity        NUMERIC(20,2) NOT NULL,
  gross_position     NUMERIC(20,2) NOT NULL,
  coverage_pct       NUMERIC(9,2)  NOT NULL,
  risk_status        VARCHAR(24)   NOT NULL,
  CONSTRAINT ck_trading_risk_status CHECK (risk_status IN ('NORMAL','WARNING','CRITICAL','NO_POSITION','PRICE_STALE'))
);
CREATE INDEX idx_trading_risk_account_time ON trading_risk_snapshot(account_id, computed_at DESC);

--rollback DROP TABLE IF EXISTS trading_risk_snapshot, trading_transfer, trading_margin_rate, trading_reservation, trading_ledger_entry, trading_balance, trading_order, trading_order_batch, trading_account CASCADE;
