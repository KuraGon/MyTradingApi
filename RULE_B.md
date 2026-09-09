# Admission des ordres — règle B

État final après correction du règlement inversé, 9 septembre 2026 : clean test et clean package verts, chacun avec 356 tests dans 38 suites, zéro failure/error/skipped. GO prêt pour revue avant commit. Les 352 tests des cycles précédents restent des preuves de l’état antérieur.

Les réservations distinguent CASH (liquidité du BUY), POSITION_CLOSE (fermeture exclusive d'une position réelle) et RISK (consommation positive de Free Equity). Un SELL ne réserve jamais son nominal en devise. Le taux de marge vient de MarginRateRepository.

Le preview et le submit recalculent les mêmes trois enveloppes. Les cotations client sont acquises avant le verrou ; les décisions locales suivent ACCOUNT, ORDER, puis réservations/soldes. La transition DRAFT vers PENDING est conditionnelle et commitée avant l'appel fournisseur. Les appels de preview/submit suspendent une éventuelle transaction appelante pour conserver cette séparation.

Le BUY réserve le maximum entre l'ancien majorant (nominal client CEILING à deux décimales, multiplié par (1 + driftTolerance), puis CEILING à six décimales) et le débit maximal réellement réglable HALF_UP à deux décimales dans l'enveloppe admise. La projection utilise les bornes du prix indicatif initial : BUY haut, SELL bas. Il n'existe aucune nouvelle tolérance. La fraîcheur de toutes les cotations est revérifiée sous verrou.

La projection valorise en liquidation client (long au SELL, short au BUY). Seule la quantité de fermeture attribuée à cet ordre réduit sa marge ; les fermetures d'autres ordres non résolus ne créditent aucune capacité. Les montants publiés à deux décimales HALF_UP sont réutilisés dans les agrégats. CASH et RISK sont deux contraintes séparées.

Une capacité disponible négative n'autorise qu'une fermeture intégrale de la quantité demandée, sans traversée de zéro, améliorant strictement la Free Equity et laissant la devise non négative. Il n'existe aucune liquidation automatique.

L'expiration ne traite que les DRAFT. PENDING et PENDING_UNKNOWN conservent tous leurs engagements, même après expires_at. FILLED consomme les réservations dans la transaction du règlement ; un second traitement FILLED est sans effet. Un rejet libère les engagements.

Pour le DTO balances, le disponible devise déduit CASH, jamais RISK. Les réservations LEGACY en devise restent déduites conservativement de la liquidité et de la capacité pendant la transition. Le disponible métal reste un solde net de fermetures SELL réservées ; il n'est pas une limite d'ouverture de short.

## Migration 011

Toutes les lignes antérieures deviennent LEGACY sans modification de statut ni d'échéance. Aucun code d'écriture ne crée LEGACY. Aucun DEFAULT SQL ne permet une sémantique implicite. L'unicité (order_id, reservation_kind, asset) est garantie ; les NULL order_id restent permis pour les historiques de batch. Un doublon historique incompatible fait échouer la migration plutôt que fusionner des engagements. Historique rapporté par la session précédente, non revérifié : 292 lignes, aucun doublon order_id/asset, aucune ligne sans ordre. Ce constat ne constitue aucune preuve pour l'UAT. Le 9 septembre, les tests utilisent la base locale historique sur autorisation explicite ; le constat « 292 lignes » n'a pas été réaudité.

## Transferts externes et futur retrait client

TransferService.ingest() reste une ingestion de mouvements externes et conserve son comportement. Aucun backend Trading vers compte commercial n'est créé.

Un futur OUT initié par le client devra vérifier atomiquement, sous le même verrou compte :

- currencyBalanceAfter >= 0 ;
- projectedFreeEquityAfter >= 0 ;
- les engagements CASH/RISK encore opposables.

## Limite de l'enveloppe

L'enveloppe borne l'admission avec les prix connus avant envoi. Elle ne transforme pas le contrat fournisseur en cotation ferme et ne garantit pas le prix d'un fill externe. Les invariants comptables et le traitement de l'incertitude restent nécessaires.

## Protection des fermetures transmises après règlement inversé — 9 septembre 2026

Reproduction PostgreSQL réelle : compte EUR sans limites, cash 0, XAU +10, prix marché/client 100, spreads nuls, marge compte 0,05 lue dans MarginRateRepository, drift 0,002. A SELL 10 transmis réserve CLOSE 10/RISK 0 ; B SELL 10 transmis réserve CLOSE 0/RISK 52. Le vrai ExecutionEventHandler règle B avant A : cash 1000, XAU 0, B FILLED/CONSUMED et A PENDING_UNKNOWN/CLOSE ACTIVE. Sur le code précédent, le preview SELL 192 ne levait pas le rejet attendu : une failure, zéro error/skipped, code Maven 1. Aucun calcul de capacité mocké, aucun FILLED forcé par SQL.

Cause confirmée : le RISK historique nul de A continuait à être utilisé alors que sa fermeture n'était plus couverte par la position comptabilisée. L'admission ignorait son exposition short devenue possible.

Correction locale : ReservationRepository relit les POSITION_CLOSE actives et leur ordre/RISK/prix indicatif, y compris après TTL pour PENDING/PENDING_UNKNOWN. OrderCapacityService attribue la position réelle une seule fois, par ordre d'identité, en plafonnant chaque attribution à la fermeture réservée. Les DRAFT opposables conservent leur attribution. Pour une fermeture transmise devenue partiellement ou totalement découverte, OrderRiskProjection recalcule l'ordre entier avec seulement la portion encore couverte. L'admission déduit en supplément `max(RISK recalculé - RISK historique, 0)` ; elle ne crédite aucune réduction future d'autrui et ne réécrit aucune réservation ou donnée d'ordre transmis. Les taux restent issus de MarginRateRepository ; prix de liquidation client, enveloppe et arrondis existants sont réutilisés. Le calcul s'exécute sous le verrou ACCOUNT puis ORDER existant, au preview comme au submit, en excluant l'ordre recalculé.

OrderExecutionService ajoute uniquement les métaux des fermetures transmises à l'instantané acquis hors transaction longue, même lorsque leur solde réel est nul. Une cotation manquante lors de la relecture sous verrou reste un rejet explicite. Aucun changement à ExecutionEventHandler, au ledger, à CASH, aux limites historiques, au drift ou aux migrations.

Preuves nouvelles dans RuleBTradingIntegrationTest :

- `reverseFillMustKeepUnbackedTransmittedCloseCovered` : SELL 192 refusé après B ; SELL 182 autorisé (RISK 946,40), submit puis règlement C avant A réels ; cash final 20200, XAU -192, Free Equity finale 40. Le complément pour A est 52 ; il laisse seulement 948 de capacité à la nouvelle admission, sans modifier son RISK persisté nul.
- `normalFillOrderKeepsRemainingOpeningRiskAndSettlesCorrectly` : A puis B restent réglables, B conserve RISK 52 entre les deux, les deux règlements consomment leurs réservations ; cash 2000, XAU -10.
- `residualPositionCannotBackTwoTransmittedCloses` : après règlement d'une ouverture de 5 oz, deux fermetures de 5 oz n'utilisent pas deux fois les 5 oz restantes. SELL 183 rejeté, SELL 182 autorisé et transmis.
- `unbackedTransmittedCloseRemainsProtectedAfterTtlWhenTradingAnotherMetal` : A reste protégé après TTL et XAU nul lors d'une admission XAG. Les cotations XAU sont acquises au preview et au submit ; SELL XAG 192 rejeté sans appel fournisseur, voisin 182 transmis, A et sa CLOSE intacts.

Environnement local et bornages de fixtures inchangés ; cotations/fournisseur simulés, tâches externes neutralisées. Aucun accès StoneX/AS400 réel, UAT ou production. Les trois corrections précédentes et le bornage de TradingDatabaseInvariantTest sont conservés.

Commandes exactes, avec JAVA_HOME, M et R définis dans la section de validation précédente ci-dessous :

```powershell
& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=RuleBTradingIntegrationTest#reverseFillMustKeepUnbackedTransmittedCloseCovered' test
$S='RuleBTradingIntegrationTest,OrderCapacityServiceTest,OrderExecutionServiceTest,ExecutionEventHandlerTest,ReservationServiceTest,OrderRiskProjectionTest'
& $M -o -B "-Dmaven.repo.local=$R" "-Dtest=$S" test
& $M -o -B "-Dmaven.repo.local=$R" clean test
& $M -o -B "-Dmaven.repo.local=$R" clean package
```

| Exécution | Code | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|---:|
| Reproduction avant correction | 1 | 1 | 1 | 1 | 0 | 0 |
| Première sélection corrigée : erreur de nom de champ à la compilation | 1 | 0 | 0 | — | — | — |
| Même sélection après correction du nom | 0 | 6 | 124 | 0 | 0 | 0 |
| clean test, incluant le nouveau cas autre métal/TTL | 0 | 38 | 356 | 0 | 0 | 0 |
| clean package, même état | 0 | 38 | 356 | 0 | 0 | 0 |

Les deux cycles complets sans sélection/exclusion/skip se terminent à 13:01:31 et 13:03:37, Europe/Paris (UTC+02:00). Comparaison des 38 XML de chaque cycle : mêmes suites et compteurs. Présence vérifiée dans chacun : OrderRiskProjectionTest 17, OrderExecutionServiceTest 16, ExecutionEventHandlerTest 3, ReservationServiceTest 1, RuleBTradingIntegrationTest 62, ReservationSemanticsMigrationTest 4, TradingDatabaseInvariantTest 13, SimulatedTradingEndToEndTest 2, As400FilledIsolationIntegrationTest 1 et OrderCapacityServiceTest 26, plus les 28 autres suites. Les passages distincts ne sont pas additionnés.

Sauvegarde préalable ciblée et rapports conservés hors target : `%TEMP%/saamp-rule-b-reverse-fill-20260909`, sous-dossiers previous-package-reports, red-reports, targeted-reports, clean-test-reports (avant le second clean), clean-package-reports. Les XML conservés sont expurgés des propriétés et sorties standard. Aucun ZIP ni transfert préparé.

Nouveau WAR effectivement construit :

```text
C:\Users\BOUZEBOUDJA\Documents\Saamp\Dev\trading_app\trading-api\target\trading-api.war
2026-09-09T13:03:37.349464+02:00
47413885 octets
SHA-256 : 64399442dd48f5eae2b4e868eb2f625f34a0dd8f4d8bd6fe40c28d57b6c36dd5
```

Les empreintes avant/après confirment que seuls OrderCapacityService, ReservationRepository, OrderExecutionService, RuleBTradingIntegrationTest et RULE_B.md ont changé pendant cette phase. Migrations 001..012/master, POM/dépendances, AS400 métier/mappings, FX, StoneX réel, ledger, TransferService.ingest(), auth, CORS, API publique et configuration de production inchangés. Aucun ordre transmis ni réservation opposable modifié par la correction d'admission.

GO prêt pour revue avant commit. Les réserves antérieures demeurent documentées : impact exact des anciens essais non reconstituable, taux manquant injecté par spy, parcours PostgreSQL complet de revue manuelle non couvert, absence d'empreinte historique complète des anciennes sources. Le préflight UAT reste ultérieur ; aucun nouveau chantier engagé.

## Historique : validation après bornage des tests, 9 septembre 2026

Seuls `TradingDatabaseInvariantTest.java` et ce document ont changé pendant cette phase. Les trois corrections métier et leurs preuves antérieures (cinq reproductions devenues vertes, puis 144 tests ciblés verts) sont conservées sans réimplémentation ni nouvelle exécution rouge.

Les cinq préparations déplaçant les échéances de toute la file outbox ont été retirées. Le petit `FixtureJdbcTemplate` privé ajoute uniquement `order_id IN (...)` dans le WHERE du CTE réel de claim, avant ORDER BY, LIMIT et FOR UPDATE SKIP LOCKED. Les paramètres, transitions, tokens, baux, mapper et transactions restent ceux du repository réel. Le périmètre est alimenté exclusivement par les ordres créés par le helper du scénario, remis à zéro avant chaque test et partagé avec les tâches concurrentes et les repositories recréés. Un périmètre vide ou une structure SQL inattendue provoque un échec avant exécution du claim ; aucune sélection globale de repli. Les préparations/finalisations restantes ciblent des identifiants de fixtures ; les UPDATE simples vérifient une ligne affectée.

Preuve : `twoSuccessiveClaimsCannotAcquireTheSameActiveOutboxEvent` crée d'abord un événement témoin éligible, extérieur au périmètre, puis l'événement à réclamer. Le premier claim prend uniquement ce dernier ; le second est vide. La comparaison SELECT * du témoin avant rollback vérifie notamment statut, échéance, token et compteurs inchangés. Les assertions existantes de retry, reclaim, identité, progression des quatre jambes et SKIP LOCKED avec connexion concurrente passent également. Aucun mock ne remplace le SQL ou les verrous PostgreSQL.

Environnement conservé : PostgreSQL localhost:5432/trading, utilisateur trading, secret TRADING_DB_PASSWORD uniquement ; JBR IntelliJ Java 21, Maven IntelliJ, dépôt existant, mode hors ligne, aucun profil Maven explicite. Fournisseur/cotations simulés ou locaux, AS400 et tâches externes neutralisés. Aucune nouvelle infrastructure ni nouveau schéma pour cette suite ; les tests DDL conservent leurs schémas temporaires vérifiés.

Commandes exactes :

```powershell
$env:JAVA_HOME='C:\Users\BOUZEBOUDJA\AppData\Local\Programs\IntelliJ IDEA Ultimate\jbr'
$M='C:\Users\BOUZEBOUDJA\AppData\Local\Programs\IntelliJ IDEA Ultimate\plugins\maven\lib\maven3\bin\mvn.cmd'
$R='C:\Users\BOUZEBOUDJA\.m2\repository'
& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=TradingDatabaseInvariantTest' test
& $M -o -B "-Dmaven.repo.local=$R" clean test
& $M -o -B "-Dmaven.repo.local=$R" clean package
```

| Passage | Code | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|---:|
| Invariants complets | 0 | 1 | 13 | 0 | 0 | 0 |
| clean test | 0 | 38 | 352 | 0 | 0 | 0 |
| clean package | 0 | 38 | 352 | 0 | 0 | 0 |

Fin des commandes : 12:20:34, 12:21:48 et 12:23:11, Europe/Paris (UTC+02:00). Les deux cycles complets utilisent la même sélection naturelle Surefire, sans filtre, exclusion ni skip. Les 38 suites et leurs compteurs ont été comparés dans les XML des deux exécutions : identiques. Présence effective confirmée dans chacun : OrderRiskProjectionTest 17, OrderExecutionServiceTest 16, ExecutionEventHandlerTest 3, ReservationServiceTest 1, RuleBTradingIntegrationTest 58, ReservationSemanticsMigrationTest 4, TradingDatabaseInvariantTest 13, SimulatedTradingEndToEndTest 2, As400FilledIsolationIntegrationTest 1, OrderCapacityServiceTest 26, ainsi que les 28 autres suites. Ces passages distincts ne sont pas additionnés.

Sauvegarde ciblée et rapports expurgés conservés dans `%TEMP%/saamp-rule-b-final-validation-20260909` : rapports antérieurs avant clean, puis `invariants-reports`, `clean-test-reports` avant le second clean et `clean-package-reports`. Aucun ZIP ou transfert de sources préparé.

Nouveau WAR réellement construit par le clean package vert :

```text
C:\Users\BOUZEBOUDJA\Documents\Saamp\Dev\trading_app\trading-api\target\trading-api.war | 2026-09-09T12:23:11.531549+02:00 | 47410663 bytes | SHA256 23e6376c310686e69a7801c10eee81fc932e2943efeafe474307d724c41b31a3
```

Contrôle par empreintes avant/après : aucun changement sous src/main (y compris fichiers non suivis, migrations 001..012 et master), ni POM/dépendances ou AGENTS.md. Les périmètres protégés et les corrections Rule B antérieures sont intacts. Working tree : main, HEAD d673e9235c0bf32f7d65d36d0b4e877ba351d859, 17 fichiers suivis modifiés et 11 non suivis, rien d'indexé. Aucun commit, push ou déploiement.

Le blocage de validation des tests est levé. Restent uniquement les limites déjà documentées : impact des premiers essais globaux et du passage interrompu non reconstituable, preuve du taux manquant par spy, parcours PostgreSQL complet de revue manuelle non couvert, absence d'empreinte historique complète des anciennes sources. Aucune investigation ou réparation des anciens essais. Préflight UAT ultérieur ; ces limites ne sont pas présentées comme levées par les tests verts.

## Historique : corrections ciblées du 9 septembre 2026, après revue finale

Les trois contre-exemples ont été reproduits avant correction : cinq tests exécutés, cinq failures attendues, zéro error/skipped (code Maven 1). La même sélection passe après correction : cinq tests, zéro failure/error/skipped (code 0).

- CASH : `OrderCapacityService` conserve le majorant historique et prend son maximum avec `HALF_UP(quantityOz * prixClientMaxAdmis, 2)`, publié à six décimales. Le spread et CEILING/FLOOR produisent déjà le prix client à `quoteScale`. Le contrôle existant compare ce prix client au prix indicatif client ; le spread n'est pas appliqué deux fois. Les bornes tiennent compte de HALF_UP à douze décimales dans `PriceMath.driftRatio` : les extrémités exactes à `tolerance + 0,5 * 10^-12` sont exclues, puis ramenées à la grille des prix client. Aucune tolérance ajoutée. Le cas 0,03 × 100 réserve maintenant 3,010000 au lieu de 3,006000. SELL reste sans CASH.
- Montant non réglable : rejet d'admission `ORDER_AMOUNT_NOT_SETTLEABLE` si le débit/crédit HALF_UP(2) à la borne client minimale admise est nul. Aucun minimum commercial ni modification du ledger. Le contrôle commun s'exécute au preview et au submit, avant libération/remplacement des réservations. Un preview rejeté ne laisse ni DRAFT ni réservation ; un submit rejeté conserve DRAFT et ses réservations antérieures. Tests BUY/SELL, demi-centime, voisin accepté, prix indicatif réglable mais borne basse nulle, grille des prix et absence d'appel fournisseur.
- Expiration : le SQL sélectionne les DRAFT même sans réservation. L'échéance est MIN(expires_at) de toutes les réservations de l'ordre, sinon created_at + TTL configuré, exactement comme submit. Après ACCOUNT puis ORDER, statut et échéance sont relus dans la même transaction Spring avant écriture. Les tests couvrent avant/après TTL sans réservation, PENDING/PENDING_UNKNOWN sans réservation, expiration gagnante sans transmission, relecture de l'échéance après attente du verrou, et admission gagnante avec conservation des trois réservations. Barrières explicites et délais bornés, sans sleep.

Les tests d'expiration `RuleBTradingIntegrationTest` exécutent le vrai SQL avec un prédicat additionnel sur le compte synthétique courant. Le résolveur end-to-end conserve son bornage antérieur ; fournisseur et cotations sont simulés, tâches externes désactivées. Les migrations restent dans leurs schémas temporaires vérifiés. Aucune migration 001..012 ni master modifiés pendant cette phase ; aucun changement à ExecutionEventHandler, OrderExecutionService, au ledger ou aux périmètres protégés.

Sauvegarde locale ciblée préalable, comprenant les fichiers non suivis concernés et les rapports historiques expurgés : `%TEMP%/saamp-rule-b-fixes-20260909-114840`. Les rapports courants expurgés sont dans `safe-targeted-reports`. Aucun ZIP, dossier de transfert ou copie de WAR.

Commandes exécutées avec les variables M/R et JAVA_HOME définies dans la section historique suivante, sans profil Maven, options communes `-o -B "-Dmaven.repo.local=$R"` :

```powershell
$F='OrderCapacityServiceTest#cashCoversRoundedDebitAtAdmittedClientPrice,RuleBTradingIntegrationTest#cashRoundingRejectsInsufficientPreviewWithoutPartialWrites+minimumQuantityCannotBeTransmitted+draftWithoutReservationExpiresAtFallbackDeadline'
$S='OrderRiskProjectionTest,OrderExecutionServiceTest,ExecutionEventHandlerTest,ReservationServiceTest,RuleBTradingIntegrationTest,ReservationSemanticsMigrationTest,SimulatedTradingEndToEndTest,As400FilledIsolationIntegrationTest,OrderCapacityServiceTest,OrderQueryServiceTest,PendingUnknownResolverTest,RiskCalculatorTest,RiskServiceTest'
& $M -o -B "-Dmaven.repo.local=$R" "-Dtest=$F" test # rouge, puis vert après correction
& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=OrderRiskProjectionTest,OrderExecutionServiceTest,ExecutionEventHandlerTest,ReservationServiceTest,RuleBTradingIntegrationTest,ReservationSemanticsMigrationTest,TradingDatabaseInvariantTest,SimulatedTradingEndToEndTest,As400FilledIsolationIntegrationTest,OrderCapacityServiceTest,OrderQueryServiceTest,PendingUnknownResolverTest,RiskCalculatorTest,RiskServiceTest' test # interrompu
& $M -o -B "-Dmaven.repo.local=$R" "-Dtest=$S" test
```

| Passage | Code | Tests terminés | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| Reproduction avant correction | 1 | 5 | 5 | 0 | 0 |
| Même sélection corrigée | 0 | 5 | 0 | 0 | 0 |
| Sélection de 14 suites interrompue | 1 | 65 | 0 | 0 | 0 |
| Sélection sûre de 13 suites | 0 | 144 | 0 | 0 | 0 |
| clean test | non exécuté | — | — | — | — |
| clean package | non exécuté | — | — | — | — |

Les 65 tests de la sélection interrompue ne constituent pas une validation de l'ensemble sélectionné : le fork JVM a été arrêté volontairement. Les passes distinctes ne s'additionnent pas.

Rapports actuels : OrderCapacityServiceTest 26 ; RuleBTradingIntegrationTest 58 ; OrderRiskProjectionTest 17 ; OrderExecutionServiceTest 16 ; ExecutionEventHandlerTest 3 ; ReservationServiceTest 1 ; ReservationSemanticsMigrationTest 4 ; SimulatedTradingEndToEndTest 2 ; As400FilledIsolationIntegrationTest 1 ; OrderQueryServiceTest 5 ; PendingUnknownResolverTest 4 ; RiskCalculatorTest 1 ; RiskServiceTest 6. Tous à zéro failure/error/skipped. Huit des neuf suites obligatoires sont validées actuellement ; TradingDatabaseInvariantTest reste non validée. Les quatre tests de migration passent, dont installation complète et montée 010 → 011.

**Blocage de validation et protection des données** : `TradingDatabaseInvariantTest`, notamment lignes 162, 209, 247, 288 et 340, déplace globalement les échéances de l'outbox puis fait rollback. Ces écritures restent incompatibles avec l'interdiction de toucher aux données étrangères aux fixtures. Détecté pendant la première sélection, le fork a été interrompu. Le journal confirme le début de cette suite et son initialisation Liquibase (zéro changeset exécuté), sans rapport de fin ; il ne permet pas d'établir quels tests/mutations ont commencé. Impact non reconstituable avec les preuves disponibles. Aucun nettoyage ni réparation entrepris. Les cycles complets ne sont pas lancés pour ne pas réexécuter ces écritures ; aucun filtre n'est utilisé pour prétendre obtenir un cycle complet vert. Action minimale restante : borner ces tests historiques à leurs événements synthétiques, sans changer AS400 métier, puis exécuter la suite et les deux cycles complets sans exclusion.

Verdict : NO GO prêt pour revue avant commit, uniquement faute de validation complète sûre. Aucun nouveau WAR : le WAR du 9 septembre à 09:22:13.465+02:00 (47 408 798 octets) reste historique et ne contient pas ces corrections.

Les limites déjà recensées restent ouvertes : impact des premiers essais globaux non reconstituable, taux manquant injecté par spy, parcours PostgreSQL complet de revue manuelle non couvert, absence d'empreinte historique complète des sources. Le préflight UAT est ultérieur. Le fichier `CADRAGE_ASSISTANT_MYTRADING_RULE_B.md` n'a pas été retrouvé ; les consignes explicites de cette phase, AGENTS.md, le document de reprise et RULE_B.md ont servi de cadrage.

## Validation locale antérieure du 9 septembre 2026

### Corrections démontrées et frontières transactionnelles

- `OrderRiskProjection` réutilise les valorisations publiées de la position entière avant/après, puis leur marge. Deux tests rouges ont démontré un centime de Free Equity indûment crédité par l'arrondi des seules portions : fermeture partielle (18,59 attendu contre 18,60) et augmentation d'un short (45,41 de RISK attendu contre 45,40). Les expositions opposées restent séparées quand leur compensation utiliserait une fermeture réservée par autrui. Les cinq cas demandés sont testés avec BUY/SELL asymétriques.
- `OrderCapacityService` bloque preview et submit avec `LEGACY_COMMITMENT_UNRESOLVED` dès qu'un LEGACY ACTIVE du compte est lié à PENDING/PENDING_UNKNOWN, quel que soit l'actif ou le TTL. Quatre cas couvrent devise/métal et les deux états ; le rejet résout le blocage. Un LEGACY DRAFT expire normalement et les LEGACY sans ordre restent comptés conservativement.
- `ReservationRepository.expireDue` expire désormais aussi l'ordre DRAFT et toutes ses réservations actives. Le submit d'un brouillon périmé expire ses réservations au lieu de les libérer comme un rejet.
- `preview` et `submit` utilisent le proxy Spring NOT_SUPPORTED pour suspendre une transaction appelante. Les cotations sont obtenues avant `TransactionTemplate`; les écritures utilisent une vraie transaction REQUIRED autonome, avec verrou ACCOUNT puis ORDER. Il n'y a pas de frontière fondée sur une self-invocation annotée.
- Le preview valide DRAFT et toutes les réservations ensemble. Le submit recalcule capacité, CASH/CLOSE/RISK et limites sous verrou, exclut ses réservations, exige exactement une transition conditionnelle DRAFT → PENDING, puis commit avant l'appel fournisseur.
- `ReservationService.expireDue` englobe les SELECT FOR UPDATE et les UPDATE dans sa transaction Spring. `ExecutionEventHandler.handleFilled` englobe ACCOUNT → ORDER, ledger, soldes, FILLED, outbox et CONSUMED ; un FILLED déjà réglé est sans effet.
- Limites restaurées conformément à HEAD d673e9235 : deal au prix client, CEILING(2) ; position aux prix marché bid(long)/ask(short), somme brute puis HALF_UP(2), égalité admise. Aucun seuil modifié. Risk/Free Equity restent aux liquidations client.

### PostgreSQL, migrations et configuration effective

Sur instruction explicite, PostgreSQL local historique `jdbc:postgresql://localhost:5432/trading`, utilisateur `trading`, secret uniquement `TRADING_DB_PASSWORD`. Les adresses datasource/Liquibase sont fixées dans les trois contextes Spring ; JDBC direct utilise le petit helper `LocalPostgres`. Aucun Docker, Testcontainers, WSL, instance supplémentaire ou framework général. Les deux tests exclusivement consacrés à l'ancien customizer Docker ont été retirés ; aucun scénario Rule B supprimé.

Fournisseur SIMULATED ou double local, cotations synthétiques/locales ; AS400 désactivé, URL vide, réconciliation et processeur des tâches planifiées désactivés dans les contextes de test. Les tests PMX utilisent leur serveur HTTP local. Aucune configuration de production modifiée.

Les tests de migration utilisent uniquement un schéma `reservation_011_<UUID>` créé par le test, vérifié via `current_schema()` avant migration et suppression ; DROP CASCADE cible exclusivement ce nom validé, jamais public. La préparation de l'historique synthétique est commitée avant le passage Liquibase suivant. Les tests vérifient le master réel, 010 → 011, backfill sans perte, états et échéances, LEGACY sans ordre, contraintes et unicité. Des doublons historiques font échouer 011 avec rollback complet, sans suppression/fusion/libération. Le préflight de tout environnement futur doit rechercher les doublons `(order_id, asset)` non nuls et résoudre explicitement ces conflits ; aucun préflight UAT effectué.

**Migration additive 012 nécessaire** : le test end-to-end a démontré que VARCHAR(16) ne peut stocker `MANUAL_REVIEW_REQUIRED`; `PROVIDER_UNCERTAIN` et `SUBMISSION_STATE_UNKNOWN` dépassent aussi cette largeur. 012 élargit seulement `stonex_error_code` à VARCHAR(64), sans tronquer les codes ni changer l'API. 001 à 011 ne sont pas réécrites. Un test vérifie 011 → 012, conservation de la ligne et des codes historiques, puis persistance des marqueurs. L'installation complète va désormais jusqu'à 012.

Les tests de catalogue ciblent `current_schema()` : l'instance locale comporte d'autres schémas, qui rendaient faux les anciens comptages globaux de triggers/séquences. Le batch du scénario end-to-end est limité à son compte synthétique : le premier essai parcourait la file locale globale, pouvait retarder le scénario et actualiser des échéances d'ordres extérieurs. Le résolveur et le règlement testés restent les vrais composants. Les tests historiques ne sont pas refactorés globalement ; des comptes synthétiques et leurs écritures append-only subsistent dans la base locale.

### Rapports et résultats actuels

Sauvegarde préalable : `%TEMP%/saamp-rule-b-local-20260909-085621`, fichiers suivis/non suivis concernés et rapports historiques expurgés des propriétés JVM et sorties standard. Les archives de chaque validation sont séparées des résultats du 8 septembre. Runner : Surefire 3.5.5 / JUnit Platform, 38 classes `*Test`, aucun profil Maven explicite, Spring `default`, JBR Java 21, Maven IntelliJ, dépôt Maven existant et mode hors ligne `-o -B`.

Commandes PowerShell exactes (mêmes paramètres pour chaque passage, aucun profil Maven ajouté) :

```powershell
$env:JAVA_HOME='C:\Users\BOUZEBOUDJA\AppData\Local\Programs\IntelliJ IDEA Ultimate\jbr'
$M='C:\Users\BOUZEBOUDJA\AppData\Local\Programs\IntelliJ IDEA Ultimate\plugins\maven\lib\maven3\bin\mvn.cmd'
$R='C:\Users\BOUZEBOUDJA\.m2\repository'
$C='OrderRiskProjectionTest,OrderExecutionServiceTest,ExecutionEventHandlerTest,ReservationServiceTest,RuleBTradingIntegrationTest,ReservationSemanticsMigrationTest,TradingDatabaseInvariantTest,SimulatedTradingEndToEndTest,As400FilledIsolationIntegrationTest'
$D='OrderCapacityServiceTest,OrderExecutionServiceTest,ExecutionEventHandlerTest,OrderQueryServiceTest,PendingUnknownResolverTest,ReservationServiceTest,RiskCalculatorTest,RiskServiceTest,OrderRiskProjectionTest,RuleBTradingIntegrationTest'
```

| Exécution | Commande | Code | Tests | Failures | Errors | Skipped |
|---|---|---:|---:|---:|---:|---:|
| Ciblés 1 | `& $M -o -B "-Dmaven.repo.local=$R" "-Dtest=$C" clean test` | 1 | 95 | 1 | 2 | 0 |
| Arrondis rouges | `& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=OrderRiskProjectionTest' test` | 1 | 17 | 2 | 0 | 0 |
| Ciblés 2 | `& $M -o -B "-Dmaven.repo.local=$R" "-Dtest=$C" test` | 1 | 99 | 2 | 0 | 0 |
| Ciblés 3 | même commande | 1 | 99 | 1 | 0 | 0 |
| Ciblés 4, corrigés | même commande | 0 | 99 | 0 | 0 | 0 |
| Domaines et concurrence | `& $M -o -B "-Dmaven.repo.local=$R" "-Dtest=$D" test` | 0 | 102 | 0 | 0 | 0 |
| Complet | `& $M -o -B "-Dmaven.repo.local=$R" clean test` | 0 | 317 | 0 | 0 | 0 |
| Packaging complet | `& $M -o -B "-Dmaven.repo.local=$R" clean package` | 0 | 317 | 0 | 0 | 0 |

Causes des essais rouges : catalogues non bornés au schéma courant et colonne d'erreur trop courte (ciblés 1) ; deux écarts d'arrondi reproduits isolément ; historique synthétique non commité entre passages Liquibase (ciblés 2) ; batch global du scénario end-to-end précédé par la file locale historique (ciblés 3). Ces causes ont été corrigées avant le passage vert.

Rapports : `targeted-1` (9 XML initiaux), `targeted-green` (9 XML, 99 tests), `domains-green` (compteurs par suite extraits du journal Maven, 102 tests), `clean-test` (38 XML, 317 tests), sous la sauvegarde locale. Les journaux de commandes sont conservés séparément. Les XML de `clean-test` ont été archivés avant le clean du packaging. Aucun résultat n'est calculé en additionnant des rapports périmés de sélections différentes.

Les neuf suites importantes sont présentes et vertes au passage ciblé final ainsi que dans `clean test` :

| Suite | Tests | Failures / Errors / Skipped |
|---|---:|---|
| OrderRiskProjectionTest | 17 | 0 / 0 / 0 |
| OrderExecutionServiceTest | 16 | 0 / 0 / 0 |
| ExecutionEventHandlerTest | 3 | 0 / 0 / 0 |
| ReservationServiceTest | 1 | 0 / 0 / 0 |
| RuleBTradingIntegrationTest | 42 | 0 / 0 / 0 |
| ReservationSemanticsMigrationTest | 4 | 0 / 0 / 0 |
| TradingDatabaseInvariantTest | 13 | 0 / 0 / 0 |
| SimulatedTradingEndToEndTest | 2 | 0 / 0 / 0 |
| As400FilledIsolationIntegrationTest | 1 | 0 / 0 / 0 |

### Preuves transactionnelles obtenues sur Spring/PostgreSQL

Les 42 invocations Rule B comprennent : six trajectoires BUY/SELL ; quatre cas sous marge ; formule exacte de capacité short ; marge spécifique et taux manquant ; cash disponible indépendant du RISK ; devise non négative en Java/PostgreSQL et métal négatif permis ; idempotence preview ; compte suspendu ; recalcul au submit des fonds, taux et limites ; exclusion des réservations propres à capacité exacte.

Les tests avec transactions réelles démontrent le rollback d'un preview après écritures CASH/CLOSE, la suspension puis reprise de la transaction appelante et le commit autonome du preview malgré son rollback. Au submit, une connexion indépendante observe PENDING commité et prend ACCOUNT/ORDER/RESERVATION avec FOR UPDATE NOWAIT pendant l'appel fournisseur, y compris avec transaction appelante suspendue.

Les courses utilisent CountDownLatch et Future.get avec délais bornés : deux submits donnent un appel fournisseur ; deux fills donnent un règlement de deux écritures ; deux ordres n'attribuent pas deux fois la même fermeture ni la même capacité. La course expireDue/submit lit des candidats DRAFT avant le commit d'admission puis relit l'état sous verrou ; PENDING_UNKNOWN conserve les trois types après TTL. PENDING est vérifié séparément. Un échec injecté après consommation annule ledger, soldes, FILLED, outbox et réservations ensemble. Le rejet fournisseur libère ; une exception fournisseur conserve PENDING_UNKNOWN et ses trois engagements, sans retransmission.

La course ingestion externe OUT / preview BUY préserve la non-négativité, l'idempotence de l'ingestion et le rejet au submit lorsque le cash a diminué. `As400FilledIsolationIntegrationTest` vérifie le règlement local et l'outbox avec dépendances AS400 doublées. Aucun changement de la sémantique du ledger, de `TransferService.ingest()` ou du métier AS400.

### Packaging, état final et limites restantes

`clean package` a terminé avec code 0 le 9 septembre à 09:22:13 (Europe/Paris), après 317 tests dans les 38 suites attendues, toutes présentes dans les XML, sans échec/erreur/ignoré. Le WAR nouvellement construit est `target/trading-api.war` (47408798 octets). Les rapports sont archivés sous `clean-package`. Aucun cycle supplémentaire requis par Surefire ; aucun skip ni exclusion pour les deux validations complètes.

Branche `main`, HEAD `d673e9235c0bf32f7d65d36d0b4e877ba351d859`, 17 fichiers suivis modifiés, 11 non suivis, aucun changement indexé. Aucun diff sur 001 à 010 ; 011 identique à la sauvegarde. Aucun changement métier AS400, FX, fournisseur réel, ledger, transferts, API publique, auth, CORS ou configuration de production. Le seul fichier AS400 modifié est la configuration locale de son test d'intégration. Aucune dépendance Docker/Testcontainers dans le POM ni dans le WAR. Aucun secret d'environnement trouvé dans les fichiers modifiés. Aucun commit, push ou déploiement.

La validation porte sur PostgreSQL local 18.0 et des dépendances simulées. L'instance historique est partagée : les tests DML laissent des fixtures synthétiques append-only ; les tests DDL créent/suppriment exclusivement leurs schémas. L'application locale de 012 est effective ; les anciennes migrations n'ont subi aucun contournement de checksum. Une migration d'un autre environnement nécessite le préflight des doublons historiques et la revue habituelle, non exécutés ici. Les LEGACY transmis actifs bloquent volontairement l'admission jusqu'à résolution. Les limites fournisseur réel et prix de fill hors enveloppe restent celles du projet ; aucune validation UAT/production ou déploiement n'est revendiqué.

## Historique du 8 septembre 2026 — ne décrit pas la validation actuelle

Les infrastructures et blocages Docker décrits dans cette section ont été abandonnés le 9 septembre sur instruction explicite. Les résultats ci-dessous restent historiques.

Le working tree retrouvé a été conservé : branche `main`, HEAD `d673e9235c0bf32f7d65d36d0b4e877ba351d859`, initialement 14 fichiers suivis modifiés et 8 nouveaux fichiers. Aucun commit, push, déploiement, changement de branche, restauration ou opération Git destructive.

Sauvegarde hors dépôt : `%TEMP%/saamp-rule-b-backup-20260908-223228`, comprenant les 22 fichiers retrouvés, AGENTS.md, pom.xml et 37 rapports historiques. Les propriétés JVM et sorties standard/erreur ont été retirées des copies des rapports pour ne pas archiver la configuration locale. Les rapports historiques restent distincts des validations actuelles. Aucun Maven clean n'a été lancé.

### Corrections ciblées

- `OrderCapacityService` : limite position au marché bid pour un long, ask pour un short ; somme des valeurs brutes puis HALF_UP à deux décimales, comme HEAD. Le contrôle reste strictement `>` : égalité au plafond autorisée.
- Limite deal inchangée : quantité × prix client du sens de l'ordre, CEILING à deux décimales. Aucun seuil modifié.
- `OrderExecutionService.preview` : acquisition distincte des cotations fraîches nécessaires à la limite position, avant les verrous ; le prix indicatif du preview reste celui d'affichage. Risk/Free Equity continuent à utiliser les liquidations client.
- `OrderCapacityServiceTest` distingue prix marché/prix client, long/short, frontières et arrondi après agrégation des métaux.
- `OrderExecutionServiceTest` rétablit le compte suspendu, y compris une suspension entre cotation et verrou, le verrou global d'exécution, l'actif désactivé, la quantité minimale et la fraîcheur des limites au preview.
- Javadocs des frontières transactionnelles complétées ; contradiction Rule A/Rule B corrigée dans AGENTS.md, ainsi que l'obligation d'échouer explicitement si Docker est indisponible.
- Aucun DTO public modifié. Aucun changement de comportement supplémentaire du ledger, de TransferService.ingest(), d'AS400 ou de l'adaptateur fournisseur réel.

### Isolation des tests

`pom.xml` ajoute Testcontainers PostgreSQL 1.21.3, version disponible dans le cache local (le parent proposerait 1.21.4, absent du cache lors du premier essai). Image prévue : `postgres:16-alpine`, base et identifiants synthétiques, ports dynamiques, réutilisation désactivée. Aucun repli JDBC vers localhost:5432/trading.

`IsolatedPostgres` est l'unique source de connexion PostgreSQL, y compris pour les accès JDBC directs des suites de migration et d'invariants. `IsolatedContextCustomizerFactory`, enregistré via `src/test/resources/META-INF/spring.factories`, couvre tous les `@SpringBootTest`, dont le test AS400 existant, sans modifier ces classes AS400.

La datasource et l'adresse Liquibase sont imposées par le conteneur ; l'environnement applicatif ne choisit pas leur destination. Fournisseur SIMULATED imposé (ou doubles spécifiques des suites), URL AS400 vide, AS400/réconciliation désactivés. Le processeur des méthodes planifiées est retiré avant l'instanciation des beans. Un décodeur JWT de test échoue localement plutôt que contacter JWKS. La configuration de production est inchangée.

Les deux tests unitaires de l'isolation confirment la découverte du customizer et la priorité de ces paramètres face à une configuration contradictoire synthétique, sans ouvrir de connexion. Le démarrage d'un contexte applicatif complet sur PostgreSQL reste non validé faute de Docker.

Les tests de l'adaptateur PMX utilisent un serveur HTTP local et des jetons synthétiques ; aucune exécution ni cotation fournisseur réelle. Les tests AS400 exécutés utilisent des doubles JDBC/gateway.

### Runner et commandes réellement exécutées

Java : JBR 21.0.10. Runner observé : Surefire 3.5.5, JUnit Platform. Aucun profil Maven explicite ; Spring utilise `default` pour le test MVC. Les `*Test` relèvent du cycle `test` ; aucun Failsafe ni cycle supplémentaire ajouté. Les sélections ci-dessous constituent une validation PARTIELLE, jamais une validation complète avec exclusions.

Variables PowerShell utilisées pour décrire exactement les commandes :

```powershell
$env:JAVA_HOME='C:\Users\BOUZEBOUDJA\AppData\Local\Programs\IntelliJ IDEA Ultimate\jbr'
$M='C:\Users\BOUZEBOUDJA\AppData\Local\Programs\IntelliJ IDEA Ultimate\plugins\maven\lib\maven3\bin\mvn.cmd'
$R='C:\Users\BOUZEBOUDJA\.m2\repository'
$S6='OrderExecutionServiceTest,ExecutionEventHandlerTest,ReservationServiceTest,OrderRiskProjectionTest,RiskCalculatorTest,RiskServiceTest'
$S7='OrderCapacityServiceTest,OrderExecutionServiceTest,ExecutionEventHandlerTest,ReservationServiceTest,OrderRiskProjectionTest,RiskCalculatorTest,RiskServiceTest'
$S34='AccountConfigCommandTest,As400ConfigurationTest,As400FxSourceTest,As400MovementGroupTest,As400ReconciliationServiceTest,As400SyncMigrationTest,As400SyncOutboxRepositoryTest,As400SyncWorkerTest,ClientConsultationControllerTest,ClientOrderIdFactoryTest,ExecutionEventHandlerTest,IsolatedContextCustomizerFactoryTest,JdbcAs400AccountReaderTest,JdbcAs400MovementGatewayTest,MarketDataRefreshServiceTest,OrderCapacityServiceTest,OrderExecutionServiceTest,OrderQueryServiceTest,OrderRiskProjectionTest,OrderViewTest,PendingUnknownResolverTest,PmxConnectJsonTest,PmxConnectTradingProviderTest,PmxTokenInspectorTest,PositionServiceTest,PriceMathTest,ReservationServiceTest,RiskCalculatorTest,RiskServiceTest,SicouviMovementTest,SimulatedTradingProviderTest,StatementServiceTest,TradingPairTest,TroyWeightConverterTest'
```

| Commande | Code | Tests | Failures | Errors | Skipped | Résultat |
|---|---:|---:|---:|---:|---:|---|
| `& $M -o -B "-Dtest=$S6" test` | 1 | 0 | 0 | 0 | 0 | Dépôt Maven par défaut inaccessible, avant tests |
| `& $M -o -B "-Dmaven.repo.local=$R" "-Dtest=$S6" test` | 1 | 0 | 0 | 0 | 0 | Dépendance Testcontainers 1.21.4 absente en mode hors ligne |
| Même commande après fixation de 1.21.3 | 1 | 0 | 0 | 0 | 0 | Accès au JAR commons-io refusé au compilateur dans le sandbox |
| Même commande avec accès local élargi accordé | 0 | 31 | 0 | 0 | 0 | Six suites unitaires réussies |
| `& $M -o -B "-Dmaven.repo.local=$R" "-Dtest=$S7" test` | 0 | 41 | 0 | 0 | 0 | Sept suites unitaires réussies après corrections et premiers ajouts |
| `& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=ReservationSemanticsMigrationTest' test` | 1 | 3 | 0 | 3 | 0 | Échec d'initialisation Docker ; aucune connexion PostgreSQL ni migration |
| `& $M -o -B "-Dmaven.repo.local=$R" "-Dtest=$S34" test` | 0 | 249 | 0 | 0 | 0 | 34 suites unitaires/MVC réussies, incluant les ajouts finaux |

Les compteurs à zéro des trois premiers essais signifient que Surefire n'a exécuté aucun test, pas que le build était vert. La commande de migration rapporte trois tests en erreur avant leur logique SQL. Aucun test ignoré.

La dernière exécution a compilé les 124 sources de production et les 42 sources de test présentes à cet instant. Les seules retouches de code suivantes étaient des Javadocs, sans changement exécutable.

Rapports actuels archivés sous la sauvegarde : `validation-before-final-units` (sept suites et erreurs Docker), puis `validation-final-units` (34 XML et `summary.json`). Ne pas agréger aveuglément tout `target/surefire-reports`, qui conserve aussi des résultats historiques d'autres suites.

### Résultats unitaires finaux par suite

Toutes les lignes ci-dessous ont zéro failure, error et skipped, sur l'exécution à 249 tests.

| Suite | Tests |
|---|---:|
| `AccountConfigCommandTest` | 2 |
| `As400ConfigurationTest` | 3 |
| `As400FxSourceTest` | 9 |
| `As400MovementGroupTest` | 7 |
| `As400ReconciliationServiceTest` | 1 |
| `As400SyncMigrationTest` | 6 |
| `As400SyncOutboxRepositoryTest` | 8 |
| `As400SyncWorkerTest` | 31 |
| `ClientConsultationControllerTest` | 25 |
| `ClientOrderIdFactoryTest` | 1 |
| `ExecutionEventHandlerTest` | 3 |
| `IsolatedContextCustomizerFactoryTest` | 2 |
| `JdbcAs400AccountReaderTest` | 4 |
| `JdbcAs400MovementGatewayTest` | 23 |
| `MarketDataRefreshServiceTest` | 1 |
| `OrderCapacityServiceTest` | 7 |
| `OrderExecutionServiceTest` | 16 |
| `OrderQueryServiceTest` | 5 |
| `OrderRiskProjectionTest` | 9 |
| `OrderViewTest` | 2 |
| `PendingUnknownResolverTest` | 4 |
| `PmxConnectJsonTest` | 9 |
| `PmxConnectTradingProviderTest` | 21 |
| `PmxTokenInspectorTest` | 1 |
| `PositionServiceTest` | 8 |
| `PriceMathTest` | 3 |
| `ReservationServiceTest` | 1 |
| `RiskCalculatorTest` | 1 |
| `RiskServiceTest` | 6 |
| `SicouviMovementTest` | 25 |
| `SimulatedTradingProviderTest` | 1 |
| `StatementServiceTest` | 2 |
| `TradingPairTest` | 1 |
| `TroyWeightConverterTest` | 1 |

### Preuves PostgreSQL préparées, mais non obtenues

Les méthodes ajoutées à `RuleBTradingIntegrationTest` ont été compilées, mais pas exécutées :

- échec après écritures CASH/POSITION_CLOSE au preview, avec assertions d'absence de DRAFT et de réservation résiduelle ;
- transaction appelante réelle autour du preview puis rollback : vérification du maintien de la transaction appelante et du commit autonome ;
- transaction appelante autour du submit puis rollback : le double fournisseur utilise une connexion indépendante pour voir PENDING et prendre ACCOUNT/ORDER/RESERVATION avec FOR UPDATE NOWAIT ;
- échec après consommation des réservations au règlement : assertions de rollback ledger, soldes, outbox, FILLED et réservations ;
- expiration concurrente : échéance de fixture avancée après contrôle initial du submit, candidats lus par l'expiration, barrières CountDownLatch bornées, puis vérification de la conservation des trois types ;
- PENDING après TTL, compte suspendu, exclusion des propres réservations ;
- course entre ingestion externe OUT et preview BUY, idempotence du transfert et rejet au submit si les fonds ont diminué.

Les scénarios déjà présents (six cas BUY/SELL, sous-marge, taux spécifique/manquant, double submit, double handleFilled, double fermeture, double capacité, REJECTED/CONSUMED et devise/métal) sont conservés. Leur réussite sur PostgreSQL n'est pas démontrée pendant cette session.

La non-régression du verrou commun dans les mouvements externes reste à établir en exécutant ces tests. Les tests unitaires AS400 sur doubles passent ; cela ne prouve pas les transactions PostgreSQL.

### Migration 011

Le fichier 011 est identique octet pour octet à la sauvegarde du working tree retrouvé. Les fichiers 001 à 010 sont inchangés par rapport à HEAD. Aucun changeset modifié, aucun clearCheckSums ni modification manuelle de DATABASECHANGELOG.

Trois tests de migration sont préparés : installation complète par le master réel ; installation arrêtée aux dix premiers changesets du même master, historique synthétique puis 011 ; doublons historiques provoquant l'échec atomique de 011. L'historique synthétique couvre les états de réservation, les quantités à six décimales, les échéances, PENDING/PENDING_UNKNOWN expirés et les LEGACY sans ordre. Les assertions vérifient contraintes, unicité, comptage conservateur et absence de suppression/libération arbitraire.

Résultat réel : Docker introuvable, trois erreurs d'initialisation, aucune installation ni montée 010 → 011 exécutée. Le préflight d'un environnement futur devra rechercher les doublons `(order_id, asset)` non nuls avant 011, préserver les engagements transmis et résoudre explicitement les conflits historiques. Aucun préflight UAT n'a été effectué. L'application locale antérieure rapportée demeure non revérifiée.

### Blocage, packaging et état final

Docker local n'est pas disponible. Conformément à la consigne d'arrêt des validations PostgreSQL dans ce cas, les suites `RuleBTradingIntegrationTest`, `TradingDatabaseInvariantTest`, `SimulatedTradingEndToEndTest` et `As400FilledIsolationIntegrationTest` n'ont pas été lancées. `mvn clean test` et `mvn clean package` n'ont pas été exécutés. Aucun skipTests, maven.test.skip, désactivation de test ou succès de packaging annoncé.

Un `target/trading-api.war` préexistant a été constaté (8 septembre 2026 à 13:42:09, 47 393 674 octets). Il ne provient pas de cette validation et ne constitue pas un livrable validé. Le futur packaging devra produire `target/trading-api.war` après passage complet des tests.

Contrôles finaux : aucun diff sur 001 à 010, AS400 (production et tests), fournisseur réel, ledger, transferts et application.yml ; 011 identique à la sauvegarde. Le working tree comporte 16 fichiers suivis modifiés et 13 fichiers non suivis, aucun fichier indexé. Les contrôles de whitespace Git sont consignés dans le rapport de session.

Reprise minimale : rendre Docker local disponible, vérifier le démarrage isolé, exécuter les suites PostgreSQL et corriger leurs éventuels échecs ; lancer ensuite `mvn clean test`, archiver les rapports, puis `mvn clean package`, sans exclusion et en contrôlant chaque suite et chaque compteur. Aucun résultat PostgreSQL, Liquibase ou packaging ne doit être déduit des tests unitaires ci-dessus.
