--liquibase formatted sql

--changeset saamp:014-signed-internal-balance-projection
-- A livrer exclusivement avec EffectiveBalance : le ledger enregistre des faits,
-- les disponibilites officielles sont controlees par l'admission avant engagement.
DROP TRIGGER trg_trading_balance_currency_non_negative ON trading_balance;
DROP FUNCTION check_trading_balance_currency_non_negative();
