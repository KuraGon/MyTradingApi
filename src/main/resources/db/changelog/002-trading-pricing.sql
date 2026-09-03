--liquibase formatted sql

--changeset saamp:002-trading-pricing
CREATE TABLE trading_market_price (
  pair         VARCHAR(8)    PRIMARY KEY,
  bid          NUMERIC(20,6) NOT NULL,
  ask          NUMERIC(20,6) NOT NULL,
  mid          NUMERIC(20,6) NULL,
  price_as_of  TIMESTAMPTZ   NOT NULL,
  source       VARCHAR(32)   NOT NULL DEFAULT 'PMXCONNECT',
  updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_trading_market_price_positive CHECK (bid > 0 AND ask > 0 AND ask >= bid)
);

CREATE TABLE trading_spread (
  id              BIGSERIAL PRIMARY KEY,
  company_id      BIGINT        NOT NULL,
  asset           VARCHAR(8)    NOT NULL,
  spread_buy      NUMERIC(9,6)  NOT NULL CHECK (spread_buy > 0 AND spread_buy < 1),
  spread_sell     NUMERIC(9,6)  NOT NULL CHECK (spread_sell > 0 AND spread_sell < 1),
  config_version  INTEGER       NOT NULL,
  active_from     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  active_to       TIMESTAMPTZ   NULL,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_trading_spread_asset CHECK (asset IN ('XAU','XAG','XPT','XPD')),
  CONSTRAINT ck_trading_spread_window CHECK (active_to IS NULL OR active_to > active_from),
  CONSTRAINT uq_trading_spread_version UNIQUE (company_id, asset, config_version)
);
CREATE INDEX idx_trading_spread_current ON trading_spread(company_id, asset, active_from DESC);

-- Implementation support for configurable order constraints explicitly required by the spec.
CREATE TABLE trading_asset_config (
  asset             VARCHAR(8)    PRIMARY KEY,
  min_quantity_oz   NUMERIC(20,6) NOT NULL CHECK (min_quantity_oz > 0),
  quote_scale       INTEGER       NOT NULL CHECK (quote_scale BETWEEN 0 AND 6),
  drift_tolerance   NUMERIC(9,6)  NOT NULL CHECK (drift_tolerance >= 0 AND drift_tolerance < 1),
  enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
  updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_trading_asset_config_asset CHECK (asset IN ('XAU','XAG','XPT','XPD'))
);

-- Global execution gate. Reconciliation can close it immediately when positions diverge.
CREATE TABLE trading_execution_gate (
  id            SMALLINT     PRIMARY KEY,
  open          BOOLEAN      NOT NULL DEFAULT TRUE,
  reason        VARCHAR(128) NULL,
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_trading_execution_gate_singleton CHECK (id = 1)
);

--rollback DROP TABLE IF EXISTS trading_execution_gate, trading_asset_config, trading_spread, trading_market_price CASCADE;
