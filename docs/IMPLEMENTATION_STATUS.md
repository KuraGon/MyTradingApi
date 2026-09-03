# État d'implémentation — 20/08/2026

## Lot 2.1 — Socle

- Comptes / soldes : fait
- Contrainte devise non négative : fait (app + trigger PostgreSQL)
- Ledger append-only : fait (service + trigger anti UPDATE/DELETE)
- Réservations : fait
- Transferts idempotents : service fait, transport externe à arbitrer
- Taux de marge : fait
- Moteur de risque : fait
- Fixture StoneX : fait

## Lot 2.2 — Prix / tarification

- Market prices locaux : fait
- Fraîcheur 30 s / 5 min : fait
- Spread BUY / SELL versionné : fait
- Arrondi favorable SAAMP : fait
- Raw Bid/Ask masqués des routes client : fait
- Drift tolerance 0,20 % configurable : fait

## Lot 2.3 — Exécution

- TradingProvider : fait
- Provider simulé : fait
- Pré-ordre règle A : fait
- Idempotence / ClOrdId : fait
- PRICE_MOVED : fait
- PENDING_UNKNOWN : fait
- GetRequestStatus orchestration : fait côté domaine
- PMXConnect GetSpotRates MTL/FOR : fait sur payloads production observés
- PMXConnect GetCmdtyPositions : fait sur payload production observé
- PMXConnect GetRequestStatus : fait, y compris décision spécifique code 631
- PMXConnect /Trade : BLOQUÉ — TokenID UAT / payloads succès et InProcess manquants

## Lot 2.4 — Exploitation

- Execution gate : table + service fait
- Réconciliation générique exacte + execution gate : fait, désactivé par défaut; à brancher sur provider live dédié
- Relevé structuré JSON : fait
- Rendu PDF : à faire
- Supervision MyPilot : à faire

## Revue v0.1 — corrections v0.2

- Fixture StoneX : corrigée à `Free Equity = 904 919,20`.
- `RiskCalculator` : arrondi `HALF_UP` des agrégats publiés avant réutilisation dans la chaîne de calcul.
- `position_limit` : valorisation avec `quoteForExecution`, donc refus `MARKET_PRICE_STALE` si un prix n'est pas frais.
- Tests d'invariants : ajoutés pour règle A, réservations, dérive de prix, état indéterminé, idempotence, isolation société, prix stale et suspension.
- Tests PostgreSQL/Testcontainers : concurrence des réservations, non-négativité devise, ledger append-only, application Liquibase 001–005 et présence des déclencheurs.
- Correctif de compilation dans `PendingUnknownResolver` : la variable d'ordre reste disponible dans le bloc de récupération d'erreur.

## Corrections PMXConnect v0.3

- Mapper JSON dédié : casse insensible + `BigDecimal` natif.
- Prix : fusion MTL + FOR avec horodatage commun.
- Positions : `AccountCode` persistant, distinct du `ClientId` TokenID.
- Paires : concaténation sans séparateur.
- États indéterminés : `631` seul conclut à un ordre jamais transmis ; les autres erreurs maintiennent l'incertitude.
- `401/403` ferment la barrière de transmission.
- TokenID : cohérence d'environnement et expiration contrôlées au démarrage.
- `/Trade` reste volontairement non implémenté tant que l'UAT ne permet pas d'observer le contrat réel.
