--liquibase formatted sql

--changeset saamp:012-order-error-code-width
-- Les marqueurs internes PROVIDER_UNCERTAIN et MANUAL_REVIEW_REQUIRED depassent 16 caracteres.
-- Elargissement sans perte pour persister l'incertitude et la revue manuelle sans changer les codes publics.
ALTER TABLE trading_order ALTER COLUMN stonex_error_code TYPE VARCHAR(64);
