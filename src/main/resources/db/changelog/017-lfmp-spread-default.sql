--liquibase formatted sql
--changeset saamp:017-lfmp-spread-default
CREATE TABLE trading_spread_default (
  id BIGSERIAL PRIMARY KEY,
  scope_ste VARCHAR(8) NOT NULL,
  asset VARCHAR(8) NOT NULL CHECK (asset IN ('XAU','XAG','XPT','XPD')),
  spread_buy NUMERIC(20,6) NOT NULL CHECK (spread_buy >= 0),
  spread_sell NUMERIC(20,6) NOT NULL CHECK (spread_sell >= 0),
  spread_type VARCHAR(16) NOT NULL CHECK (spread_type IN ('PERCENTAGE','ABSOLUTE')),
  price_unit VARCHAR(8) NOT NULL CHECK (price_unit='OZ'),
  config_version INTEGER NOT NULL,
  active_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  active_to TIMESTAMPTZ NULL,
  UNIQUE(scope_ste,asset,config_version),
  CHECK (active_to IS NULL OR active_to > active_from)
);
CREATE INDEX idx_trading_spread_default_current ON trading_spread_default(scope_ste,asset,active_from DESC);
INSERT INTO trading_spread_default(scope_ste,asset,spread_buy,spread_sell,spread_type,price_unit,config_version)
VALUES ('B','XAU',0.40,0.40,'ABSOLUTE','OZ',1),('B','XAG',0.10,0.10,'ABSOLUTE','OZ',1),
       ('B','XPT',1.00,1.00,'ABSOLUTE','OZ',1),('B','XPD',1.00,1.00,'ABSOLUTE','OZ',1);
--rollback DROP TABLE IF EXISTS trading_spread_default;
