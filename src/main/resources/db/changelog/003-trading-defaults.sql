--liquibase formatted sql

--changeset saamp:003-trading-defaults
INSERT INTO trading_margin_rate (account_id, asset, rate) VALUES
  (NULL, 'XAU', 0.0500),
  (NULL, 'XAG', 0.0700),
  (NULL, 'XPT', 0.1200),
  (NULL, 'XPD', 0.1200),
  (NULL, 'EUR', 0.0300),
  (NULL, 'USD', 0.0300);

INSERT INTO trading_asset_config (asset, min_quantity_oz, quote_scale, drift_tolerance) VALUES
  ('XAU', 0.000001, 6, 0.002000),
  ('XAG', 0.000001, 6, 0.002000),
  ('XPT', 0.000001, 6, 0.002000),
  ('XPD', 0.000001, 6, 0.002000);

INSERT INTO trading_execution_gate (id, open, reason) VALUES (1, TRUE, NULL);

--rollback DELETE FROM trading_execution_gate WHERE id=1; DELETE FROM trading_asset_config; DELETE FROM trading_margin_rate WHERE account_id IS NULL;
