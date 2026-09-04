--liquibase formatted sql

--changeset saamp:007-as400-ste-width
ALTER TABLE trading_account
  ALTER COLUMN as400_ste TYPE VARCHAR(1);

ALTER TABLE trading_account
  ADD CONSTRAINT ck_trading_account_as400_ste_length
  CHECK (as400_ste IS NULL OR LENGTH(as400_ste) = 1);

--rollback ALTER TABLE trading_account DROP CONSTRAINT IF EXISTS ck_trading_account_as400_ste_length;
--rollback ALTER TABLE trading_account ALTER COLUMN as400_ste TYPE VARCHAR(8);
