--liquibase formatted sql
--changeset saamp:018-global-spread-default
ALTER TABLE trading_spread_default ALTER COLUMN scope_ste DROP NOT NULL;
UPDATE trading_spread_default SET scope_ste=NULL;
DROP INDEX IF EXISTS idx_trading_spread_default_current;
ALTER TABLE trading_spread_default
  DROP CONSTRAINT IF EXISTS trading_spread_default_scope_ste_asset_config_version_key;
CREATE UNIQUE INDEX uq_trading_spread_default_global_asset_version
  ON trading_spread_default(asset,config_version);
CREATE INDEX idx_trading_spread_default_global_current
  ON trading_spread_default(asset,active_from DESC);
--rollback DROP INDEX IF EXISTS uq_trading_spread_default_global_asset_version;
--rollback DROP INDEX IF EXISTS idx_trading_spread_default_global_current;
