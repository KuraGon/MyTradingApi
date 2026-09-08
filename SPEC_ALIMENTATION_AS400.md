# Alimentation AS400 — groupes MyTrading V1

## Périmètre et source de vérité

PostgreSQL et le ledger restent la vérité temps réel. La transaction FILLED, les deux écritures ledger, les balances et la création de l'outbox restent inchangées. Aucun accès DB2 ou FX n'a lieu sur ce chemin critique. Aucune écriture directe dans CLCPDP03 ou FMPRO.PCGMLFCM.

Un ordre FILLED produit un événement SICOUVI et **quatre mouvements physiques** :
CLIENT, INTERCO_LFMP, INTERCO_SAAMP, STONEX.

| Rôle | Société | NUCLI | BUY client | SELL client | Prix source EUR/oz ou USD/oz |
|---|---|---|---|---|---|
| CLIENT | B | NUCLI trading figé du client | V | A | client_price publié |
| INTERCO_LFMP | B | 15001 | A | V | market_price du FILLED |
| INTERCO_SAAMP | I | 15002 | V | A | market_price du FILLED |
| STONEX | I | 15267 EUR / 15268 USD | A | V | market_price du FILLED |

Les valeurs comptables sont centralisées dans As400TradingMapping et configurables sous trading.as400. Le compte client doit être rattaché à la société LFMP configurée ; une autre société est bloquée avec AS400_CLIENT_COMPANY_NOT_LFMP. La casse STE n'est pas transformée. Le NUCLI commercial ne sert jamais de repli.

market_price est le Rate/FillPrice fourni à ExecutionEventHandler et persisté par markFilled, pas une nouvelle cotation. SIREF3 reçoit exactement stonex_exid, identique pour les quatre jambes : null/blanc ou plus de 20 caractères bloque la synchronisation, sans troncature.

**SIMULATED reste inchangé** : ses EXID actuels SIM-UUID dépassent 20 caractères et sont donc bloqués. Les tests du flux AS400 utilisent des EXID compatibles. Aucun raccourcissement du provider ni autorisation StoneX réelle n'est inclus.

## Migration 010 et historique

009 est immutable. 010 ajoute trading_as400_movement, les colonnes parent expected_leg_count, fx_rate, fx_frozen_at, fx_pair, fx_source, stonex_exid et workflow_version. Les nouveaux événements sont version 2, avec quatre jambes attendues. La structure et l'agrégation permettent N jambes, mais le constructeur comptable V1 exige quatre jambes.

Les contraintes exactes ck_trading_as400_sicouvi_identity et ck_trading_as400_progress de 009 sont retirées dans 010 et remplacées par des contrôles adaptés. Le parent conserve ses anciennes colonnes sicoui/siprov pour audit ; le nouveau runtime ne les alimente plus.

Chaque enfant possède sa propre identité SICOUI, son SIPROV, ses données d'INSERT figées, sa progression et ses horodatages. Les contraintes contrôlent types/bornes, identité obligatoire, UNIQUE(event_id,leg_index), unicité SICOUI au sein du groupe, SIPROV requis pour ACCEPTED/SETTLED et les horodatages requis. SICOUI seul n'est jamais unique globalement. Index sur event_id via l'unicité, sicoui, siprov, siref3 ; l'index order_id existant du parent est conservé.

Tous les anciens événements SICOUVI non SYNCED sont conservés en version 1 et bloqués pour revue manuelle avec AS400_LEGACY_SINGLE_MOVEMENT_REQUIRES_MANUAL_REVIEW. Leur dernier message antérieur est sauvegardé dans legacy_last_error. Identités, progression et horodatages historiques sont conservés. Les anciens SYNCED restent intacts. Aucun événement mono-ligne n'est transformé en quatre mouvements ou rejoué automatiquement. Les cibles WEIGHT_ACCOUNT et ACCOUNTING restent exclues.

**Avant migration et remplacement du binaire : arrêter/désactiver tous les anciens workers.** Le nouveau worker ne réclame que workflow_version=2. Pas de déploiement automatique, pas de rattrapage historique automatique.

## Préparation durable et FX

Le worker conserve PROCESSING, claim_token UUID, FOR UPDATE SKIP LOCKED, bail/reclaim et contrôle de propriété sous verrou PostgreSQL.

1. Première transaction PostgreSQL REQUIRES_NEW sous claim : lire le FILLED, valider l'EXID et les données, obtenir le FX, construire les quatre jambes, allouer quatre SICOUI via la séquence existante, puis persister FX et mouvements.
2. Commit de cette préparation **avant tout accès DB2**.
3. Seconde transaction PostgreSQL sous le même claim, revérifié : recharger exclusivement les mouvements persistés et appeler DB2. Le verrou empêche le reclaim pendant un appel actif ; si le token a changé entre les deux phases, aucun envoi.
4. Persister les progrès individuels et agrégés, libérer le claim.

EUR : SITXCH = 1.00, source EUR_PARITY.
USD : récupération EURUSD via TradingProvider.fetchSpotRates, sans aucune soumission de trade. La convention validee est EURUSD.mid, neutre et commune aux quatre jambes, quel que soit le sens du trade. TRADING_AS400_FX_QUOTE_FIELD=MID est la valeur attendue en UAT et le defaut explicite du backend. BID/ASK ou une valeur vide sont refuses avec AS400_FX_CONVENTION_INVALID ; un MID absent ou inexploitable reste retryable, sans ligne DB2.

FX figé = HALF_UP(EURUSD, 2 décimales). fx_rate, fx_frozen_at, fx_pair=EURUSD et fx_source (provider + champ choisi) sont persistés. Tous les retries réutilisent les mêmes mouvements et le même FX, sans nouvelle cotation ni réallocation SICOUI. Une préparation déjà figée conserve également fx_rate si les jambes ne sont pas encore présentes.

La séquence PostgreSQL reste bornée 1..999999, cyclique. Chaque jambe reçoit une valeur distincte persistée. Un warning signale une allocation à partir de 800000.

## Mapping SICOUVI inchangé hors groupe, prix et EXID

- SIPROV = 0 à l'insertion ; SITYCO = '20'.
- SIMET : XAU O, XAG A, XPT P, XPD D.
- SIPDS = HALF_UP(quantity_oz × 31.1034768, 2).
- SICOT = HALF_UP(prix_oz × 1000 / (31.10348 × SITXCH), 0), stocké à scale 4, donc EUR/kg.
- Exemple USD : EURUSD brut 1.17234 → SITXCH 1.17 ; le diviseur SICOT utilise exactement 1.17.
- SIREF1 MYTRADING, SIRELO blanc, SIREF4 0.
- SICND : OR SPOT / ARGENT SPOT / PT SPOT / PD SPOT.
- SIREF2 : OR SPOT / ARGENT SPO / PT SPOT / PD SPOT.
- SIDTEC, SIDEAC et SIHEUC : executed_at, Europe/Paris, YYMMDD et HHMMSS.
- CREDAT/CREHEU : même instant de création pour le groupe ; MAJDAT/MAJHEU = 0.

BigDecimal exclusivement, une seule division finale SICOT. Poids arrondi à zéro, dépassement DECIMAL ou mapping impossible : BLOCKED/FAILED, aucun ajustement artificiel et aucune modification du FILLED.

## Transaction DB2 et idempotence

Le gestionnaire local DataSourceTransactionManager existant conserve READ_COMMITTED, REQUIRES_NEW et timeout 30 secondes. READ_COMMITTED limite la contention sur SICOUVI1 partagé avec les traitements historiques. Aucun XA/2PC.

Dans une seule transaction DB2 :
- relire les quatre corrélations SISTE + SICOUI + SICLI + SIREF3 ;
- vérifier aussi SIACFV, SIMET, SIPDS, SICOT et SITXCH ;
- si 4/4 conformes, réutiliser leurs SIPROV sans INSERT ;
- si 0/4, insérer les quatre lignes puis effectuer un seul COMMIT ;
- si 1/4, 2/4 ou 3/4, bloquer avec AS400_PARTIAL_GROUP_REQUIRES_REVIEW, sans compléter les lignes manquantes.

Une erreur avant commit entraîne le rollback de toute la transaction. Après erreur JDBC ou commit incertain, le read-back s'exécute dans une nouvelle transaction : 4/4 confirme la soumission ; 0/4 permet le retry ; partiel impose la revue. Un read-back indisponible ne déclenche aucun resend : le prochain essai reprend d'abord le lookup complet.

**UAT à vérifier sur IBM i : journalisation et commitment control de SICOUVI1, droits, rollback réel, contention avec MySAAMP et batch.** Le code conserve le mécanisme transactionnel existant ; il ne configure ni ne présume la journalisation réelle du fichier. Aucun repli vers des commits par jambe ou autocommit n'est prévu.

## Progression

Chaque jambe progresse PENDING → SUBMITTED → ACCEPTED → SETTLED. ACCEPTED exige SIPROV > 0. SETTLED exige une ligne PROVISP1 corrélée par STE, NUPROV et NUCLI avec ETPRO1='O'. ETPRO2 ne sert jamais de critère ; une ligne absente ou ETPRO1 blanc conserve ACCEPTED.

Le parent est le minimum des progrès, **avec vérification du nombre attendu** :
- SUBMITTED uniquement si les quatre mouvements sont confirmés ;
- ACCEPTED uniquement avec quatre SIPROV ;
- SETTLED uniquement avec quatre confirmations définitives.

Les pollings peuvent conserver des jambes ACCEPTED ou SETTLED pendant que les autres attendent. Les horodatages et provisoires sont persistés. Aucun nouvel INSERT après SUBMITTED. Toute anomalie de groupe partiel requiert une revue manuelle.

status reste exclusivement technique : PENDING, RETRY, PROCESSING, BLOCKED, SYNCED. Un claim ne change jamais sync_state. Après polling réussi, status=PENDING ; après SETTLED, SYNCED. Erreur temporaire : RETRY et backoff existant de 15 secondes à une heure. Erreur non récupérable : BLOCKED et sync_state=FAILED. Les logs ne contiennent aucun secret.

## Configuration

Les délais et la datasource AS400 existants sont conservés. Sans URL, l'application démarre ; un worker activé sans datasource reste retryable. JTOpen 21.0.6 et le packaging WAR sont inchangés.

| Propriété sous trading.as400 | Variable | Défaut |
|---|---|---|
| enabled | TRADING_AS400_ENABLED | false |
| sync-delay | TRADING_AS400_SYNC_DELAY | 30s |
| submitted-poll-delay | TRADING_AS400_SUBMITTED_POLL_DELAY | 5m |
| accepted-poll-delay | TRADING_AS400_ACCEPTED_POLL_DELAY | 1h |
| lfmp-company | TRADING_AS400_LFMP_COMPANY | B |
| saamp-company | TRADING_AS400_SAAMP_COMPANY | I |
| lfmp-saamp-interco-nucli | TRADING_AS400_LFMP_SAAMP_INTERCO_NUCLI | 15001 |
| saamp-lfmp-interco-nucli | TRADING_AS400_SAAMP_LFMP_INTERCO_NUCLI | 15002 |
| stonex-eur-nucli | TRADING_AS400_STONEX_EUR_NUCLI | 15267 |
| stonex-usd-nucli | TRADING_AS400_STONEX_USD_NUCLI | 15268 |
| fx-quote-field | TRADING_AS400_FX_QUOTE_FIELD | MID, convention neutre validee pour UAT |

Aucune API publique, règle pricing/risk, opération provider, réservation, auth ou CORS n'est modifiée. Les lectures de réconciliation AS400 existantes sont conservées ; elles ne participent pas au critère SETTLED.
