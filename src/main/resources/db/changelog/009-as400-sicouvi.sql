--liquibase formatted sql

--changeset saamp:009-as400-sicouvi
CREATE SEQUENCE trading_as400_sicoui_seq MINVALUE 1 MAXVALUE 999999 START 1 CYCLE;

ALTER TABLE trading_as400_sync_outbox
  DROP CONSTRAINT ck_trading_as400_sync_target,
  DROP CONSTRAINT ck_trading_as400_sync_status,
  ADD COLUMN sync_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN sicoui INTEGER,
  ADD COLUMN siprov INTEGER,
  ADD COLUMN as400_ste VARCHAR(1),
  ADD COLUMN nucli_trading INTEGER,
  ADD COLUMN submitted_at TIMESTAMPTZ,
  ADD COLUMN accepted_at TIMESTAMPTZ,
  ADD COLUMN settled_at TIMESTAMPTZ,
  ADD COLUMN last_checked_at TIMESTAMPTZ,
  ADD CONSTRAINT ck_trading_as400_sync_target CHECK (target IN ('WEIGHT_ACCOUNT','ACCOUNTING','SICOUVI')),
  ADD CONSTRAINT ck_trading_as400_sync_status CHECK (status IN ('PENDING','RETRY','PROCESSING','BLOCKED','SYNCED')),
  ADD CONSTRAINT ck_trading_as400_sync_state CHECK (sync_state IN ('PENDING','SUBMITTED','ACCEPTED','SETTLED','FAILED')),
  ADD CONSTRAINT ck_trading_as400_sicoui CHECK (sicoui BETWEEN 1 AND 999999),
  ADD CONSTRAINT ck_trading_as400_siprov CHECK (siprov BETWEEN 1 AND 999999),
  ADD CONSTRAINT ck_trading_as400_sicouvi_identity CHECK (target <> 'SICOUVI' OR sicoui IS NOT NULL),
  ADD CONSTRAINT ck_trading_as400_progress CHECK (
    (sync_state NOT IN ('SUBMITTED','ACCEPTED','SETTLED') OR submitted_at IS NOT NULL)
    AND (sync_state NOT IN ('ACCEPTED','SETTLED') OR (siprov IS NOT NULL AND accepted_at IS NOT NULL))
    AND (sync_state <> 'SETTLED' OR settled_at IS NOT NULL));

-- Aucun rejeu implicite : les anciennes cibles nécessitent une analyse opérateur.
UPDATE trading_as400_sync_outbox SET status='BLOCKED',
  last_error='AS400_LEGACY_TARGET_REQUIRES_MANUAL_REVIEW'
WHERE target IN ('WEIGHT_ACCOUNT','ACCOUNTING') AND status <> 'SYNCED';

DROP INDEX idx_trading_as400_sync_due;
CREATE INDEX idx_trading_as400_sync_due ON trading_as400_sync_outbox(next_attempt_at,id)
  WHERE target='SICOUVI' AND status IN ('PENDING','RETRY','PROCESSING')
    AND sync_state IN ('PENDING','SUBMITTED','ACCEPTED');
CREATE INDEX idx_trading_as400_sicoui ON trading_as400_sync_outbox(sicoui) WHERE sicoui IS NOT NULL;
CREATE INDEX idx_trading_as400_siprov ON trading_as400_sync_outbox(siprov) WHERE siprov IS NOT NULL;
