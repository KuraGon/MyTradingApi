--liquibase formatted sql

--changeset saamp:010-as400-movement-group
-- Les identites et progres mono-ligne restent intacts pour audit, jamais convertis.
ALTER TABLE trading_as400_sync_outbox
  DROP CONSTRAINT ck_trading_as400_sicouvi_identity,
  DROP CONSTRAINT ck_trading_as400_progress,
  ADD COLUMN workflow_version INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN expected_leg_count INTEGER,
  ADD COLUMN fx_rate NUMERIC(7,5),
  ADD COLUMN fx_frozen_at TIMESTAMPTZ,
  ADD COLUMN fx_pair VARCHAR(6),
  ADD COLUMN fx_source VARCHAR(128),
  ADD COLUMN stonex_exid VARCHAR(20),
  ADD COLUMN legacy_last_error VARCHAR(512),
  ADD CONSTRAINT ck_trading_as400_group_version CHECK (workflow_version IN (1,2)),
  ADD CONSTRAINT ck_trading_as400_group_count CHECK (
    workflow_version <> 2 OR (expected_leg_count IS NOT NULL AND expected_leg_count > 0)),
  ADD CONSTRAINT ck_trading_as400_group_fx CHECK (
    (fx_rate IS NULL AND fx_frozen_at IS NULL)
    OR (fx_rate IS NOT NULL AND fx_rate > 0 AND fx_rate = ROUND(fx_rate,2)
        AND fx_frozen_at IS NOT NULL AND fx_source IS NOT NULL AND stonex_exid IS NOT NULL
        AND LENGTH(TRIM(stonex_exid)) > 0)),
  ADD CONSTRAINT ck_trading_as400_group_progress CHECK (
    (sync_state NOT IN ('SUBMITTED','ACCEPTED','SETTLED') OR submitted_at IS NOT NULL)
    AND (sync_state NOT IN ('ACCEPTED','SETTLED') OR accepted_at IS NOT NULL)
    AND (sync_state <> 'SETTLED' OR settled_at IS NOT NULL)
    AND (workflow_version <> 2 OR sync_state NOT IN ('SUBMITTED','ACCEPTED','SETTLED')
         OR (fx_rate IS NOT NULL AND fx_frozen_at IS NOT NULL AND stonex_exid IS NOT NULL)));

UPDATE trading_as400_sync_outbox
SET legacy_last_error=last_error,status='BLOCKED',claim_token=NULL,last_error='AS400_LEGACY_SINGLE_MOVEMENT_REQUIRES_MANUAL_REVIEW'
WHERE target='SICOUVI' AND workflow_version=1 AND status <> 'SYNCED';

ALTER TABLE trading_as400_sync_outbox ALTER COLUMN workflow_version SET DEFAULT 2;
ALTER TABLE trading_as400_sync_outbox ALTER COLUMN expected_leg_count SET DEFAULT 4;

CREATE TABLE trading_as400_movement (
  id BIGSERIAL PRIMARY KEY,
  event_id BIGINT NOT NULL REFERENCES trading_as400_sync_outbox(id) ON DELETE RESTRICT,
  leg_index INTEGER NOT NULL CHECK (leg_index >= 0),
  leg_role VARCHAR(24) NOT NULL CHECK (leg_role IN ('CLIENT','INTERCO_LFMP','INTERCO_SAAMP','STONEX')),
  siste VARCHAR(1) NOT NULL CHECK (LENGTH(TRIM(siste))=1),
  nucli INTEGER NOT NULL CHECK (nucli BETWEEN 1 AND 999999),
  sicoui INTEGER NOT NULL CHECK (sicoui BETWEEN 1 AND 999999),
  siprov INTEGER CHECK (siprov BETWEEN 1 AND 999999),
  siacfv VARCHAR(1) NOT NULL CHECK (siacfv IN ('A','V')),
  simet VARCHAR(1) NOT NULL CHECK (simet IN ('O','A','P','D')),
  sipds NUMERIC(9,2) NOT NULL CHECK (sipds > 0),
  sicot NUMERIC(10,4) NOT NULL CHECK (sicot > 0),
  sitxch NUMERIC(7,5) NOT NULL CHECK (sitxch > 0 AND sitxch=ROUND(sitxch,2)),
  siref3 VARCHAR(20) NOT NULL CHECK (LENGTH(TRIM(siref3)) > 0),
  sicnd VARCHAR(15) NOT NULL,
  siref2 VARCHAR(10) NOT NULL,
  execution_date INTEGER NOT NULL,
  execution_time INTEGER NOT NULL,
  sync_state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
    CHECK (sync_state IN ('PENDING','SUBMITTED','ACCEPTED','SETTLED','FAILED')),
  submitted_at TIMESTAMPTZ,
  accepted_at TIMESTAMPTZ,
  settled_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_error VARCHAR(512),
  CONSTRAINT uq_trading_as400_movement_leg UNIQUE(event_id,leg_index),
  CONSTRAINT uq_trading_as400_movement_identity UNIQUE(event_id,sicoui),
  CONSTRAINT ck_trading_as400_movement_progress CHECK (
    (sync_state NOT IN ('SUBMITTED','ACCEPTED','SETTLED') OR submitted_at IS NOT NULL)
    AND (sync_state NOT IN ('ACCEPTED','SETTLED') OR (siprov IS NOT NULL AND accepted_at IS NOT NULL))
    AND (sync_state <> 'SETTLED' OR settled_at IS NOT NULL))
);
CREATE INDEX idx_trading_as400_movement_sicoui ON trading_as400_movement(sicoui);
CREATE INDEX idx_trading_as400_movement_siprov ON trading_as400_movement(siprov) WHERE siprov IS NOT NULL;
CREATE INDEX idx_trading_as400_movement_exid ON trading_as400_movement(siref3);

DROP INDEX idx_trading_as400_sync_due;
CREATE INDEX idx_trading_as400_sync_due ON trading_as400_sync_outbox(next_attempt_at,id)
  WHERE target='SICOUVI' AND workflow_version=2
    AND status IN ('PENDING','RETRY','PROCESSING')
    AND sync_state IN ('PENDING','SUBMITTED','ACCEPTED');
