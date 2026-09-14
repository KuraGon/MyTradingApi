--liquibase formatted sql

--changeset saamp:016-absolute-spread-support splitStatements:false endDelimiter:;
-- Les valeurs historiques restent des ratios, sans conversion numerique.
ALTER TABLE trading_spread
  ADD COLUMN spread_type VARCHAR(16) NOT NULL DEFAULT 'PERCENTAGE',
  ADD COLUMN price_unit VARCHAR(4) NOT NULL DEFAULT 'OZ',
  ALTER COLUMN spread_buy TYPE NUMERIC(20,6),
  ALTER COLUMN spread_sell TYPE NUMERIC(20,6),
  DROP CONSTRAINT trading_spread_spread_buy_check,
  DROP CONSTRAINT trading_spread_spread_sell_check,
  ADD CONSTRAINT ck_spread_type CHECK (spread_type IN ('PERCENTAGE','ABSOLUTE')),
  ADD CONSTRAINT ck_spread_unit CHECK (price_unit='OZ'),
  ADD CONSTRAINT ck_spread_values CHECK (
    (spread_type='PERCENTAGE' AND spread_buy>0 AND spread_buy<1 AND spread_sell>0 AND spread_sell<1)
    OR (spread_type='ABSOLUTE' AND spread_buy>=0 AND spread_sell>=0));

ALTER TABLE trading_order
  ALTER COLUMN spread_applied TYPE NUMERIC(20,6),
  ADD COLUMN spread_type VARCHAR(16) NOT NULL DEFAULT 'PERCENTAGE',
  ADD COLUMN spread_quote_currency VARCHAR(3) GENERATED ALWAYS AS (right(pair,3)) STORED,
  ADD COLUMN spread_price_unit VARCHAR(4) NOT NULL DEFAULT 'OZ',
  ADD COLUMN spread_quote_scale INTEGER NOT NULL DEFAULT 6,
  ADD CONSTRAINT ck_order_spread_type CHECK (spread_type IN ('PERCENTAGE','ABSOLUTE')),
  ADD CONSTRAINT ck_order_spread_currency CHECK (spread_quote_currency IN ('EUR','USD') AND pair=asset||spread_quote_currency),
  ADD CONSTRAINT ck_order_spread_unit CHECK (spread_price_unit='OZ'),
  ADD CONSTRAINT ck_order_spread_scale CHECK (spread_quote_scale BETWEEN 0 AND 6),
  ADD CONSTRAINT ck_order_spread_value CHECK (
    (spread_type='PERCENTAGE' AND (spread_applied IS NULL OR (spread_applied>=0 AND spread_applied<1)))
    OR (spread_type='ABSOLUTE' AND spread_applied IS NOT NULL AND spread_applied>=0 AND spread_config_version IS NOT NULL));

CREATE FUNCTION protect_order_spread_snapshot() RETURNS TRIGGER AS $$
BEGIN
  IF ROW(NEW.spread_type,NEW.spread_applied,NEW.spread_config_version,NEW.spread_price_unit,NEW.spread_quote_scale,NEW.pair,NEW.asset)
     IS DISTINCT FROM ROW(OLD.spread_type,OLD.spread_applied,OLD.spread_config_version,OLD.spread_price_unit,OLD.spread_quote_scale,OLD.pair,OLD.asset) THEN
    RAISE EXCEPTION 'ORDER_SPREAD_SNAPSHOT_IMMUTABLE';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_order_spread_snapshot BEFORE UPDATE ON trading_order
  FOR EACH ROW EXECUTE FUNCTION protect_order_spread_snapshot();

-- Ne jamais supprimer silencieusement la semantique d'un montant deja utilise.
--rollback DO ' BEGIN IF EXISTS (SELECT 1 FROM trading_spread WHERE spread_type=''ABSOLUTE'') OR EXISTS (SELECT 1 FROM trading_order WHERE spread_type=''ABSOLUTE'') THEN RAISE EXCEPTION ''ABSOLUTE_SPREAD_ROLLBACK_REQUIRES_MANUAL_REVIEW''; END IF; END; ';
--rollback DROP TRIGGER trg_order_spread_snapshot ON trading_order; DROP FUNCTION protect_order_spread_snapshot();
--rollback ALTER TABLE trading_order DROP CONSTRAINT ck_order_spread_value, DROP CONSTRAINT ck_order_spread_type, DROP CONSTRAINT ck_order_spread_currency, DROP CONSTRAINT ck_order_spread_unit, DROP CONSTRAINT ck_order_spread_scale, DROP COLUMN spread_type, DROP COLUMN spread_quote_currency, DROP COLUMN spread_price_unit, DROP COLUMN spread_quote_scale, ALTER COLUMN spread_applied TYPE NUMERIC(9,6);
--rollback ALTER TABLE trading_spread DROP CONSTRAINT ck_spread_values, DROP CONSTRAINT ck_spread_type, DROP CONSTRAINT ck_spread_unit, DROP COLUMN spread_type, DROP COLUMN price_unit, ALTER COLUMN spread_buy TYPE NUMERIC(9,6), ALTER COLUMN spread_sell TYPE NUMERIC(9,6), ADD CONSTRAINT trading_spread_spread_buy_check CHECK(spread_buy>0 AND spread_buy<1), ADD CONSTRAINT trading_spread_spread_sell_check CHECK(spread_sell>0 AND spread_sell<1);
