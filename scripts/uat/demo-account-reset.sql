-- Preparation only. Run with psql -X; never use -a/-e or log operator-supplied amounts.
-- Required psql variables: account_id EUR USD XAU XAG XPT XPD reset_by.
-- reset_by identifies the actual operator; the DB session user and transaction are also recorded.
\set ON_ERROR_STOP on
\set ECHO none
\set VERBOSITY terse
\set QUIET on
BEGIN;
SET LOCAL search_path = public, pg_catalog;
SET LOCAL lock_timeout = '5s';
DO $$ BEGIN
  IF current_database() <> 'trading_uat' THEN
    RAISE EXCEPTION 'DEMO reset is restricted to trading_uat';
  END IF;
END $$;

-- Missing variables cause a syntax error and rollback, never an implicit default.
CREATE TEMP TABLE demo_reset_parameters ON COMMIT DROP AS
SELECT :'account_id'::text account_id, :'reset_by'::text reset_by;
CREATE TEMP TABLE demo_reset_targets(asset text, supplied text) ON COMMIT DROP;
INSERT INTO demo_reset_targets VALUES ('EUR', :'EUR'), ('USD', :'USD'), ('XAU', :'XAU'),
  ('XAG', :'XAG'), ('XPT', :'XPT'), ('XPD', :'XPD');

DO $$
DECLARE target_id bigint; operator_name text; item record; before_value numeric; target_value numeric;
        reset_reference text := txid_current()::text; affected integer;
BEGIN
  IF EXISTS (SELECT 1 FROM demo_reset_parameters WHERE account_id !~ '^[1-9][0-9]{0,17}$'
       OR length(trim(reset_by)) NOT BETWEEN 1 AND 64)
     OR EXISTS (SELECT 1 FROM demo_reset_targets WHERE supplied !~ '^-?[0-9]{1,14}(\.[0-9]{1,6})?$') THEN
    RAISE EXCEPTION 'Missing or invalid explicit DEMO reset parameters';
  END IF;
  SELECT account_id::bigint, trim(reset_by) INTO target_id, operator_name FROM demo_reset_parameters;
  PERFORM 1 FROM trading_account WHERE id=target_id AND account_mode='DEMO' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'DEMO account does not exist'; END IF;
  IF EXISTS (SELECT 1 FROM trading_order WHERE account_id=target_id AND trading_mode='DEMO'
              AND status IN ('DRAFT','PENDING','PENDING_UNKNOWN'))
     OR EXISTS (SELECT 1 FROM trading_reservation WHERE account_id=target_id AND trading_mode='DEMO' AND status='ACTIVE') THEN
    RAISE EXCEPTION 'Resolve active DEMO orders/reservations before reset';
  END IF;
  FOR item IN SELECT * FROM demo_reset_targets ORDER BY asset LOOP
    target_value := item.supplied::numeric(20,6);
    SELECT quantity INTO before_value FROM trading_demo_balance
      WHERE account_id=target_id AND asset=item.asset FOR UPDATE;
    before_value := COALESCE(before_value,0);
    INSERT INTO trading_demo_ledger_entry(account_id,asset,delta,entry_type,order_id,adjustment_reference,balance_after,created_by)
      VALUES (target_id,item.asset,target_value-before_value,'DEMO_ADJUSTMENT',NULL,
              reset_reference,target_value,operator_name);
    INSERT INTO trading_demo_balance(account_id,asset,quantity) VALUES (target_id,item.asset,target_value)
      ON CONFLICT(account_id,asset) DO UPDATE SET quantity=EXCLUDED.quantity,updated_at=NOW();
  END LOOP;
  RAISE NOTICE 'DEMO reset account_id=%, assets=6, reference=%, db_operator=%', target_id,reset_reference,session_user;
END $$;
COMMIT;
