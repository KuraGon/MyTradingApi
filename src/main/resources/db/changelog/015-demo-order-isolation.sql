--liquibase formatted sql

--changeset saamp:015-demo-order-isolation splitStatements:false endDelimiter:;
-- All historical orders become LIVE. DEMO orders are isolated from every AS400 path.
ALTER TABLE trading_account
  ADD COLUMN account_mode VARCHAR(8) NOT NULL DEFAULT 'LIVE',
  ADD CONSTRAINT ck_trading_account_mode CHECK (account_mode IN ('LIVE','DEMO')),
  ADD CONSTRAINT uq_trading_account_id_mode UNIQUE (id,account_mode);

CREATE FUNCTION forbid_trading_account_mode_change() RETURNS TRIGGER AS $$
BEGIN
  IF NEW.account_mode IS DISTINCT FROM OLD.account_mode THEN
    RAISE EXCEPTION 'trading_account.account_mode is immutable; provision a dedicated account';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_trading_account_mode_immutable BEFORE UPDATE OF account_mode ON trading_account
  FOR EACH ROW EXECUTE FUNCTION forbid_trading_account_mode_change();

ALTER TABLE trading_order
  ADD COLUMN trading_mode VARCHAR(8) NOT NULL DEFAULT 'LIVE',
  ADD CONSTRAINT ck_trading_order_mode CHECK (trading_mode IN ('LIVE','DEMO')),
  ADD CONSTRAINT fk_trading_order_account_mode FOREIGN KEY (account_id,trading_mode)
    REFERENCES trading_account(id,account_mode);

ALTER TABLE trading_reservation
  ADD COLUMN trading_mode VARCHAR(8) NOT NULL DEFAULT 'LIVE',
  ADD CONSTRAINT ck_trading_reservation_mode CHECK (trading_mode IN ('LIVE','DEMO')),
  ADD CONSTRAINT fk_trading_reservation_account_mode FOREIGN KEY (account_id,trading_mode)
    REFERENCES trading_account(id,account_mode);

CREATE INDEX idx_trading_order_account_mode_executed
  ON trading_order(account_id,trading_mode,executed_at DESC);

CREATE OR REPLACE FUNCTION forbid_trading_order_mode_change()
RETURNS TRIGGER AS '
BEGIN
  IF NEW.trading_mode IS DISTINCT FROM OLD.trading_mode THEN
    RAISE EXCEPTION ''trading_order.trading_mode is immutable'';
  END IF;
  RETURN NEW;
END;
' LANGUAGE plpgsql;

CREATE TRIGGER trg_trading_order_mode_immutable
  BEFORE UPDATE OF trading_mode ON trading_order
  FOR EACH ROW EXECUTE FUNCTION forbid_trading_order_mode_change();

CREATE TABLE trading_demo_balance (
  account_id BIGINT NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  asset VARCHAR(8) NOT NULL,
  quantity NUMERIC(20,6) NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (account_id,asset),
  CONSTRAINT ck_trading_demo_balance_asset CHECK (asset IN ('EUR','USD','XAU','XAG','XPT','XPD'))
);

CREATE TABLE trading_demo_ledger_entry (
  id BIGSERIAL PRIMARY KEY,
  account_id BIGINT NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  asset VARCHAR(8) NOT NULL,
  delta NUMERIC(20,6) NOT NULL,
  entry_type VARCHAR(24) NOT NULL,
  order_id BIGINT REFERENCES trading_order(id) ON DELETE RESTRICT,
  adjustment_reference VARCHAR(64),
  balance_after NUMERIC(20,6) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by VARCHAR(64) NOT NULL,
  CONSTRAINT ck_trading_demo_ledger_asset CHECK (asset IN ('EUR','USD','XAU','XAG','XPT','XPD')),
  CONSTRAINT ck_trading_demo_ledger_type CHECK (
    (entry_type='TRADE' AND order_id IS NOT NULL AND adjustment_reference IS NULL AND delta<>0)
    OR (entry_type='DEMO_ADJUSTMENT' AND order_id IS NULL AND adjustment_reference IS NOT NULL))
);
CREATE INDEX idx_trading_demo_ledger_account ON trading_demo_ledger_entry(account_id,created_at DESC);
CREATE INDEX idx_trading_demo_ledger_order ON trading_demo_ledger_entry(order_id);

CREATE OR REPLACE FUNCTION forbid_trading_demo_ledger_mutation()
RETURNS TRIGGER AS '
BEGIN
  RAISE EXCEPTION ''trading_demo_ledger_entry est append-only'';
END;
' LANGUAGE plpgsql;

CREATE TRIGGER trg_trading_demo_ledger_append_only
  BEFORE UPDATE OR DELETE ON trading_demo_ledger_entry
  FOR EACH ROW EXECUTE FUNCTION forbid_trading_demo_ledger_mutation();

CREATE OR REPLACE FUNCTION reject_demo_as400_outbox()
RETURNS TRIGGER AS '
BEGIN
  IF EXISTS (SELECT 1 FROM trading_order WHERE id=NEW.order_id AND trading_mode=''DEMO'') THEN
    RAISE EXCEPTION ''DEMO orders cannot enter the AS400 outbox'';
  END IF;
  RETURN NEW;
END;
' LANGUAGE plpgsql;

CREATE TRIGGER trg_reject_demo_as400_outbox
  BEFORE INSERT OR UPDATE OF order_id ON trading_as400_sync_outbox
  FOR EACH ROW EXECUTE FUNCTION reject_demo_as400_outbox();

CREATE FUNCTION enforce_trading_ledger_mode() RETURNS TRIGGER AS $$
DECLARE expected_mode VARCHAR(8); order_mode VARCHAR(8); order_account BIGINT;
BEGIN
  expected_mode := CASE WHEN TG_TABLE_NAME='trading_demo_ledger_entry' THEN 'DEMO' ELSE 'LIVE' END;
  IF TG_TABLE_NAME='trading_ledger_entry' AND NEW.entry_type='DEMO_ADJUSTMENT' THEN
    RAISE EXCEPTION 'DEMO adjustments cannot enter the LIVE ledger';
  END IF;
  IF NEW.order_id IS NOT NULL THEN
    SELECT trading_mode, account_id INTO order_mode, order_account FROM trading_order WHERE id=NEW.order_id;
    IF order_mode IS DISTINCT FROM expected_mode OR order_account IS DISTINCT FROM NEW.account_id THEN
      RAISE EXCEPTION 'Ledger order mode/account mismatch';
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_demo_ledger_order_mode BEFORE INSERT ON trading_demo_ledger_entry
  FOR EACH ROW EXECUTE FUNCTION enforce_trading_ledger_mode();
CREATE TRIGGER trg_live_ledger_order_mode BEFORE INSERT ON trading_ledger_entry
  FOR EACH ROW EXECUTE FUNCTION enforce_trading_ledger_mode();

CREATE FUNCTION enforce_trading_reservation_mode() RETURNS TRIGGER AS $$
BEGIN
  IF NEW.order_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM trading_order WHERE id=NEW.order_id AND trading_mode=NEW.trading_mode AND account_id=NEW.account_id
  ) THEN RAISE EXCEPTION 'Reservation order mode/account mismatch'; END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_reservation_order_mode BEFORE INSERT OR UPDATE ON trading_reservation
  FOR EACH ROW EXECUTE FUNCTION enforce_trading_reservation_mode();

CREATE FUNCTION enforce_trading_account_data_mode() RETURNS TRIGGER AS $$
DECLARE expected_mode VARCHAR(8);
BEGIN
  expected_mode := CASE WHEN TG_TABLE_NAME IN ('trading_demo_balance','trading_demo_ledger_entry')
    THEN 'DEMO' ELSE 'LIVE' END;
  IF NOT EXISTS (SELECT 1 FROM trading_account WHERE id=NEW.account_id AND account_mode=expected_mode) THEN
    RAISE EXCEPTION 'Account data mode mismatch';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_demo_balance_account_mode BEFORE INSERT OR UPDATE ON trading_demo_balance
  FOR EACH ROW EXECUTE FUNCTION enforce_trading_account_data_mode();
CREATE TRIGGER trg_demo_ledger_account_mode BEFORE INSERT ON trading_demo_ledger_entry
  FOR EACH ROW EXECUTE FUNCTION enforce_trading_account_data_mode();
CREATE TRIGGER trg_live_balance_account_mode BEFORE INSERT OR UPDATE ON trading_balance
  FOR EACH ROW EXECUTE FUNCTION enforce_trading_account_data_mode();
CREATE TRIGGER trg_live_ledger_account_mode BEFORE INSERT ON trading_ledger_entry
  FOR EACH ROW EXECUTE FUNCTION enforce_trading_account_data_mode();
CREATE TRIGGER trg_live_transfer_account_mode BEFORE INSERT OR UPDATE ON trading_transfer
  FOR EACH ROW EXECUTE FUNCTION enforce_trading_account_data_mode();

-- A rollback after DEMO use must not relabel historical DEMO orders as LIVE or erase its audit.
--rollback DO 'BEGIN IF EXISTS (SELECT 1 FROM trading_account WHERE account_mode=''DEMO'') OR EXISTS (SELECT 1 FROM trading_order WHERE trading_mode=''DEMO'') OR EXISTS (SELECT 1 FROM trading_demo_ledger_entry) OR EXISTS (SELECT 1 FROM trading_demo_balance) OR EXISTS (SELECT 1 FROM trading_reservation WHERE trading_mode=''DEMO'') THEN RAISE EXCEPTION ''015 used: manual archive and rollback review required''; END IF; END;';
--rollback DROP TRIGGER IF EXISTS trg_demo_balance_account_mode ON trading_demo_balance; DROP TRIGGER IF EXISTS trg_demo_ledger_account_mode ON trading_demo_ledger_entry; DROP TRIGGER IF EXISTS trg_live_balance_account_mode ON trading_balance; DROP TRIGGER IF EXISTS trg_live_ledger_account_mode ON trading_ledger_entry; DROP TRIGGER IF EXISTS trg_live_transfer_account_mode ON trading_transfer; DROP FUNCTION IF EXISTS enforce_trading_account_data_mode(); ALTER TABLE trading_order DROP CONSTRAINT fk_trading_order_account_mode; ALTER TABLE trading_reservation DROP CONSTRAINT fk_trading_reservation_account_mode; DROP TRIGGER trg_trading_account_mode_immutable ON trading_account; DROP FUNCTION forbid_trading_account_mode_change(); ALTER TABLE trading_account DROP CONSTRAINT uq_trading_account_id_mode, DROP CONSTRAINT ck_trading_account_mode, DROP COLUMN account_mode;
--rollback DROP TRIGGER IF EXISTS trg_reservation_order_mode ON trading_reservation; DROP FUNCTION IF EXISTS enforce_trading_reservation_mode(); DROP TRIGGER IF EXISTS trg_demo_ledger_order_mode ON trading_demo_ledger_entry; DROP TRIGGER IF EXISTS trg_live_ledger_order_mode ON trading_ledger_entry; DROP FUNCTION IF EXISTS enforce_trading_ledger_mode(); DROP TRIGGER IF EXISTS trg_reject_demo_as400_outbox ON trading_as400_sync_outbox; DROP FUNCTION IF EXISTS reject_demo_as400_outbox(); DROP TRIGGER IF EXISTS trg_trading_demo_ledger_append_only ON trading_demo_ledger_entry; DROP FUNCTION IF EXISTS forbid_trading_demo_ledger_mutation(); DROP TABLE IF EXISTS trading_demo_ledger_entry; DROP TABLE IF EXISTS trading_demo_balance; DROP TRIGGER IF EXISTS trg_trading_order_mode_immutable ON trading_order; DROP FUNCTION IF EXISTS forbid_trading_order_mode_change(); DROP INDEX IF EXISTS idx_trading_order_account_mode_executed; ALTER TABLE trading_reservation DROP CONSTRAINT IF EXISTS ck_trading_reservation_mode, DROP COLUMN IF EXISTS trading_mode; ALTER TABLE trading_order DROP CONSTRAINT IF EXISTS ck_trading_order_mode, DROP COLUMN IF EXISTS trading_mode;
