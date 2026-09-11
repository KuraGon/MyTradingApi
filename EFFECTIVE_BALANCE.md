# Effective Balance — compte trading officiel

## Identité et activation

La racine MyTrading est exclusivement `as400_ste + as400_nucli_trading`.
Le NUCLI commercial ne participe jamais à la lecture ni au fallback.
Le mode est `trading.effective-balance.mode=LEGACY|SHADOW|ENFORCED`, LEGACY par défaut
(variable Spring : `TRADING_EFFECTIVEBALANCE_MODE`). Aucune activation UAT automatique.

- LEGACY : affichage, risque et admission sur la projection historique.
- SHADOW : acquisition officielle et calcul du snapshot, mais décisions et vues toujours locales.
  `officialBalances + pendingAdjustments = calculatedEffectiveBalances` ;
  `decisionBalances` contient la projection PostgreSQL (legacy).
  Ces quatre champs du snapshot permettent la comparaison diagnostique sans changer la décision.
  Les logs indiquent seulement accountId et disponibilité, sans soldes ni secret.
- ENFORCED : source officielle obligatoire pour les lectures de compte et l'admission.
  `decisionBalances = calculatedEffectiveBalances` ; en LEGACY, le calcul est absent (liste vide).
  Une indisponibilité produit une erreur explicite HTTP 503, sans fallback.
  Le Risk Monitor audite l'erreur ; il ne produit pas de nouveau NORMAL sur erreur.

La fraîcheur technique d'une acquisition est bornée par
`trading.effective-balance.max-snapshot-age` (5s par défaut, strictement positive).
Cette limite est mesurée depuis le début de lecture, pas depuis sa fin.
Elle peut refuser une lecture trop lente ; ce n'est pas une autorisation d'utiliser un ancien cache.

## Lecture officielle

Le lecteur est distinct du gateway SICOUVI ; il n'exécute que des SELECT,
sur une connexion mise en lecture seule. Le worker de synchronisation peut rester disabled.

La convention actuellement implémentée est EUR / LFMP : STE B, NREPCO=400.
USD et les autres conventions société ne sont pas certifiés et échouent explicitement.
CLIENOP1 doit contenir exactement une référence (STE, NUCLI trading, NULIV=0).
Une identité absente ou ambiguë est indisponible, jamais un compte à zéro.

Poids : CLCPDP03, STE/NUCLI trading, NULIV=0, circuits O/A/P/D.
SOLD03 en grammes est divisé par 31.1034768, scale 6, HALF_UP.
Compte existant sans ligne métal : zéro ; doublons ou NULL : indisponible.

EUR : PCGMLFCM, L1SOC=LFM, L1NCA=NUCLI trading, L1LET=deux espaces.
L'identité LFMP est vérifiée séparément afin d'éviter une jointure multiplicative.
C est positif, D négatif ; les autres sens ou montants illisibles sont refusés.
Aucune écriture applicable après une lecture réussie : zéro.
La convention historique utilise L1MTT, pas L1MTD ; aucune généralisation à USD.

## Ajustements CLIENT

La propriété `trading.effective-balance.overlay-cutover-at` est un instant ISO-8601
explicitement choisi par l'exploitant (variable attendue : `TRADING_EFFECTIVE_BALANCE_OVERLAY_CUTOVER_AT`).
Le relaxed binding Spring accepte ce nom, ainsi que la forme compacte
`TRADING_EFFECTIVEBALANCE_OVERLAYCUTOVERAT` ; les deux sont vérifiés avec une vraie
`SystemEnvironmentPropertySource` dans `EffectiveBalancePropertiesTest`.
Elle n'a aucune valeur par défaut. Son absence empêche le démarrage en SHADOW/ENFORCED ;
LEGACY reste autorisé sans cutover et ne lit pas l'overlay.
Seuls les ordres FILLED avec `executed_at >= overlay-cutover-at` sont sélectionnés.
Les FILLED antérieurs sont exclus sans aucune déduction sur leur imputation ; un `executed_at`
absent ne satisfait pas la borne non plus. La sélection est reconstruite depuis PostgreSQL
avec le même instant configuré après chaque redémarrage, jamais depuis l'heure de démarrage.
Les dates fixes des tests sont uniquement des fixtures et ne configurent aucun environnement métier.

Chaque ordre FILLED sélectionné contribue une seule fois par son ID, indépendamment de PENDING,
RETRY, BLOCKED, groupe partiel ou absence d'outbox.
BUY : +quantity_oz métal, -gross_amount devise ; SELL : signes inverses.
Les deux montants persistés sont utilisés tels quels. Aucun prix courant, SIPDS ou SICOT
n'est utilisé pour reconstruire ces deltas.

Seule la jambe CLIENT est corrélée. Les trois autres jambes ne participent jamais au solde.
SIPROV peut être découvert par SELECT SICOUVI, même avant ACCEPTED PostgreSQL,
avec STE/SICOUI/NUCLI/EXID et SIREF1=MYTRADING.
PROVISP1 est lu avec STE/NUCLI/NUPROV ; seul ETPRO1.trim() exactement égal à O
retire l'ajustement. Zéro, blanc ou autre état ne le retirent pas.
Une corrélation ambiguë ou un provisoire connu introuvable rend la lecture indisponible.
Le mapping figé d'un ancien fait incompatible avec le compte courant est refusé.

Un ordre sélectionné avec CLIENT ETPRO1=O a donc un ajustement nul,
sans attendre les jambes interco/StoneX. Aucun ancien ordre de recette n'est inclus implicitement.

## Concurrence et consommateurs

Deux collectes AS400 complètes doivent concorder ; les faits PostgreSQL sont relus.
Le snapshot conserve identité, montants officiels, ajustements, montants effectifs calculés et soldes de décision,
horodatages, disponibilité et corrélations CLIENT incluses/exclues.
Il n'existe aucun cache persistant de compte officiel.

L'acquisition précède ACCOUNT/ORDER. Après attente du verrou, l'admission contrôle
l'âge, le mapping et les faits FILLED/mouvements ; toute évolution invalide l'acquisition.
En LEGACY/SHADOW, l'admission conserve sa relecture de la projection PostgreSQL sous verrou ACCOUNT.
Les réservations CASH/POSITION_CLOSE/RISK et les calculs Rule B restent ceux existants.
Les cotations incluent les positions exclusivement AS400.
La transition PENDING est commitée avant l'appel fournisseur.
Le FILLED ne relit jamais l'AS400 : ses deux écritures restent enregistrables même
si l'AS400 tombe après exécution.

La source unique alimente balances, positions, summary, PositionService, RiskService,
OrderCapacityService et le Risk Monitor. Le monitor parcourt tous les comptes en mode
officiel pour ne pas oublier une position absente de PostgreSQL.
Le calcul Risk et OrderRiskProjection ne sont pas remplacés.

Limite : deux lectures concordantes ne constituent pas un snapshot transactionnel IBM i
ni une transaction distribuée. L'imputation officielle et la publication ETPRO1=O doivent
respecter le contrat métier AS400. Une écriture externe postérieure à la dernière lecture
reste extérieure au verrou PostgreSQL. La fenêtre est bornée par max-snapshot-age ;
les optimisations futures ne doivent jamais substituer une projection locale au compte officiel.
Le coût de lecture croît avec les faits à corréler ; une lecture dépassant le délai est refusée.

### Coût du lecteur (revue en lecture seule)

Une collecte effectue 3 SELECT DB2 fixes : CLIENOP1, CLCPDP03 et PCGMLFCM.
Chaque fait avec une jambe CLIENT préparée ajoute 1 SELECT SICOUVI1 ; si un SIPROV
est connu ou découvert, il ajoute aussi 1 SELECT PROVISP1. Sans jambe CLIENT préparée,
aucun SELECT supplémentaire n'est nécessaire et l'overlay reste présent.
Une capture réussie impose deux collectes concordantes, soit **6 + 2M + 2P SELECT DB2**,
où M est le nombre de jambes CLIENT préparées et P celles ayant un SIPROV connu/découvert.
Un provisoire connu coûte donc 4 SELECT supplémentaires par capture (2 par collecte).
Les CLIENT déjà O sont également relus : le coût dépend de tous les FILLED depuis le cutover,
pas seulement des provisoires encore non imputés. Il n'existe pas de cache des CLIENT O.
Deux connexions DB2 sont ouvertes successivement ; les allers-retours et le volume lu sont doublés.
S'ajoutent 2 SELECT PostgreSQL des faits par capture réussie, puis leur revalidation locale
sous verrou en ENFORCED. Le délai de 5s par requête DB2 ne remplace pas max-snapshot-age.
Aucun regroupement, cache ou changement du lecteur n'est introduit dans cette revue.

## Projection signée et migration

014 retire seulement trg_trading_balance_currency_non_negative et sa fonction.
001..013 restent intacts. Append-only ledger, nonzero delta, clés et transactions sont conservés.
LedgerService ne rejette plus un fait parce que sa projection devise devient négative.
trading_balance reste la projection transactionnelle interne, jamais un apport officiel fictif.

014 et le code EffectiveBalance doivent être livrés ensemble ; ne jamais déployer 014 seule.
L'activation ENFORCED nécessite une décision explicite et des mappings trading validés.
Aucune restauration automatique de l'ancien trigger : un retour exige d'abord d'examiner
les projections négatives et l'admission, sans supprimer ni inventer d'écritures ledger.

TransferService.ingest reste l'enregistrement idempotent d'un mouvement externe déjà réalisé.
Un OUT peut donc laisser une projection négative ; il ne crée pas un ajustement de trade.
Une future commande de retrait doit être admise avec EffectiveBalance avant engagement.
Les statements restent l'historique MyTrading : balance_after est une projection locale,
pas un historique exhaustif des comptes AS400. Le front doit conserver cette distinction.

La réconciliation historique est conservée ; son écart projection/officiel ne doit jamais
servir à injecter automatiquement un crédit dans le ledger.

## Validation locale

Les tests utilisent uniquement les schémas saamp_test_* de LocalPostgres et des doubles AS400.
Aucune connexion AS400 réelle, écriture UAT, migration distante, activation de worker,
modification du gateway, du mapping quatre jambes, de FX MID ou de StoneX.
Les résultats exécutés et le WAR sont rapportés séparément après les cycles complets.

Les scénarios fonctionnels PostgreSQL avec AS400 doublé utilisent un max-snapshot-age de 2m pour isoler leurs assertions des lenteurs de création des connexions Windows. Les tests dédiés vérifient séparément le défaut 5s et le refus d’un snapshot expiré ; aucune configuration applicative/UAT n’est changée par ce réglage de test.

Le support LocalPostgres utilise un pool Hikari existant par schema genere, ferme avant son nettoyage. Cela evite les authentifications Windows repetitives observees dans les tests, sans modifier les delais metier. Les controles de destination, de current_schema et de propriete du schema restent appliques a chaque connexion.
