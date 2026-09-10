--liquibase formatted sql

--changeset saamp:013-risk-monitor splitStatements:false endDelimiter:;
CREATE TABLE trading_risk_monitor_state (
  account_id BIGINT PRIMARY KEY REFERENCES trading_account(id) ON DELETE RESTRICT,
  version BIGINT NOT NULL CHECK (version > 0),
  level VARCHAR(24) NULL CHECK (level IN ('NO_POSITION','NORMAL','WARNING','CRITICAL','LIQUIDATION_REQUIRED','PRICE_STALE')),
  indicators JSONB NULL CHECK (indicators IS NULL OR jsonb_typeof(indicators) = 'object'),
  price_as_of TIMESTAMPTZ NULL,
  calculated_at TIMESTAMPTZ NOT NULL,
  valid_calculated_at TIMESTAMPTZ NULL,
  gates JSONB NOT NULL CHECK (jsonb_typeof(gates) = 'object'),
  error_code VARCHAR(64) NULL,
  last_notified_level VARCHAR(24) NULL CHECK (last_notified_level IN ('WARNING','CRITICAL','LIQUIDATION_REQUIRED','PRICE_STALE')),
  last_alert_at TIMESTAMPTZ NULL,
  last_notified_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_notified_sequence >= 0)
);

CREATE TABLE trading_risk_monitor_event (
  id UUID PRIMARY KEY,
  account_id BIGINT NOT NULL REFERENCES trading_account(id) ON DELETE RESTRICT,
  transition_sequence BIGINT NOT NULL CHECK (transition_sequence > 0),
  old_level VARCHAR(24) NULL CHECK (old_level IN ('NO_POSITION','NORMAL','WARNING','CRITICAL','LIQUIDATION_REQUIRED','PRICE_STALE')),
  new_level VARCHAR(24) NULL CHECK (new_level IN ('NO_POSITION','NORMAL','WARNING','CRITICAL','LIQUIDATION_REQUIRED','PRICE_STALE')),
  indicators JSONB NULL CHECK (indicators IS NULL OR jsonb_typeof(indicators) = 'object'),
  price_as_of TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL,
  reason VARCHAR(64) NULL,
  delivery_status VARCHAR(16) NOT NULL CHECK (delivery_status IN ('AUDIT','PENDING','SENDING','RETRY','SENT','FAILED')),
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  next_attempt_at TIMESTAMPTZ NULL,
  claim_token UUID NULL,
  lease_until TIMESTAMPTZ NULL,
  sent_at TIMESTAMPTZ NULL,
  last_error VARCHAR(64) NULL,
  CONSTRAINT uq_risk_monitor_transition UNIQUE(account_id,transition_sequence),
  CONSTRAINT ck_risk_monitor_claim CHECK ((delivery_status='SENDING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (delivery_status<>'SENDING' AND claim_token IS NULL AND lease_until IS NULL)),
  CONSTRAINT ck_risk_monitor_sent CHECK ((delivery_status='SENT') = (sent_at IS NOT NULL)),
  CONSTRAINT ck_risk_monitor_failed CHECK ((delivery_status='FAILED') = (next_attempt_at IS NULL)),
  CONSTRAINT ck_risk_monitor_failure_reason CHECK (delivery_status<>'FAILED' OR last_error IS NOT NULL)
);
CREATE INDEX idx_risk_monitor_due ON trading_risk_monitor_event(next_attempt_at,created_at,id)
  WHERE delivery_status IN ('PENDING','RETRY','SENDING');
CREATE INDEX idx_risk_monitor_history ON trading_risk_monitor_event(account_id,created_at DESC);

CREATE FUNCTION protect_risk_monitor_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Risk monitor audit cannot be deleted';
  END IF;
  IF ROW(NEW.id,NEW.account_id,NEW.transition_sequence,NEW.old_level,NEW.new_level,
         NEW.indicators,NEW.price_as_of,NEW.created_at,NEW.reason)
     IS DISTINCT FROM ROW(OLD.id,OLD.account_id,OLD.transition_sequence,OLD.old_level,OLD.new_level,
         OLD.indicators,OLD.price_as_of,OLD.created_at,OLD.reason) THEN
    RAISE EXCEPTION 'Risk monitor audit payload is immutable';
  END IF;
  IF (OLD.delivery_status IN ('AUDIT','SENT','FAILED') AND NEW IS DISTINCT FROM OLD)
     OR (OLD.delivery_status IN ('PENDING','RETRY') AND NEW.delivery_status NOT IN (OLD.delivery_status,'SENDING','FAILED'))
     OR (OLD.delivery_status='SENDING' AND NEW.delivery_status NOT IN ('SENDING','RETRY','SENT','FAILED'))
     OR NEW.attempt_count < OLD.attempt_count THEN
    RAISE EXCEPTION 'Invalid risk monitor delivery transition';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_risk_monitor_audit BEFORE UPDATE OR DELETE ON trading_risk_monitor_event
  FOR EACH ROW EXECUTE FUNCTION protect_risk_monitor_event();

-- Désactivation opérationnelle : arrêter uniquement le monitor et son worker ; conserver l'audit.
-- Aucun rollback destructif automatique : suppression des tables = perte des décisions et livraisons.
