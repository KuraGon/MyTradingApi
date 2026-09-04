--liquibase formatted sql

--changeset saamp:008-as400-outbox-claim
ALTER TABLE trading_as400_sync_outbox
  DROP CONSTRAINT ck_trading_as400_sync_status;

ALTER TABLE trading_as400_sync_outbox
  ADD COLUMN claim_token UUID NULL,
  ADD CONSTRAINT ck_trading_as400_sync_status
  CHECK (status IN ('PENDING','RETRY','BLOCKED','PROCESSING','SYNCED'));

--rollback ALTER TABLE trading_as400_sync_outbox DROP CONSTRAINT IF EXISTS ck_trading_as400_sync_status;
--rollback ALTER TABLE trading_as400_sync_outbox DROP COLUMN IF EXISTS claim_token;
--rollback ALTER TABLE trading_as400_sync_outbox ADD CONSTRAINT ck_trading_as400_sync_status CHECK (status IN ('PENDING','RETRY','BLOCKED','SYNCED'));
