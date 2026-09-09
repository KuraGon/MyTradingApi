--liquibase formatted sql

--changeset saamp:011-reservation-semantics
-- Aucun engagement existant n'est libere ni reinterprete pendant la bascule.
ALTER TABLE trading_reservation ADD COLUMN reservation_kind VARCHAR(16);
UPDATE trading_reservation SET reservation_kind='LEGACY';
ALTER TABLE trading_reservation ALTER COLUMN reservation_kind SET NOT NULL;
ALTER TABLE trading_reservation ADD CONSTRAINT ck_trading_reservation_kind
  CHECK (reservation_kind IN ('LEGACY','CASH','POSITION_CLOSE','RISK'));
-- Pas de DEFAULT : chaque nouvelle ecriture doit annoncer sa semantique.
-- Aucun doublon observe avant bascule. Un historique incompatible fait echouer
-- la migration transactionnelle plutot que fusionner ou supprimer un engagement.
CREATE UNIQUE INDEX uq_trading_reservation_kind_asset
  ON trading_reservation(order_id,reservation_kind,asset);
