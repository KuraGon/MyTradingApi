# Risk Monitor â€” V1

Monitor post-position uniquement. Aucun ordre, aucune liquidation, aucune modification de Rule B, du ledger ou des rÃ©servations. Aucun appel /Trade et aucun composant AS400 dans le monitor.

## Calcul et Ã©tats

Le calcul unique de RiskCalculator fournit le rÃ©sultat historique et sa couverture avant arrondi dâ€™affichage. Les montants restent arrondis selon les conventions existantes. Gross Position est la somme des valeurs absolues des valorisations client par mÃ©tal ; Net Equity = fonds + valorisation signÃ©e ; marge = somme des marges ; Free Equity = Net Equity - marge ; couverture = 100 + 100 * Net Equity / Gross Position. Ce suivi des positions rÃ©alisÃ©es ne reprÃ©sente pas la capacitÃ© dâ€™admission nette des engagements Rule B.

NO_POSITION est traitÃ© avant le pourcentage sentinelle. NORMAL >105 ; WARNING ]104,105] ; CRITICAL ]102,104] ; LIQUIDATION_REQUIRED <=102. Aucun changement de lâ€™ancien RiskStatus/API.

Les comptes suspendus encore exposÃ©s restent suivis. Les comptes prÃ©cÃ©demment suivis sont relus jusquâ€™Ã  NO_POSITION.

## Configuration externe, dÃ©sactivation par dÃ©faut

Aucun fichier de configuration de production nâ€™est modifiÃ©. Sans `trading.risk-monitor.enabled=true`, aucun monitor, worker ni paramÃ¨tre SMTP nâ€™est requis.

Ã€ lâ€™activation, les propriÃ©tÃ©s suivantes sont obligatoires (aucune valeur mÃ©tier implicite) :

- `trading.risk-monitor.interval` : durÃ©e entre passages ;
- `trading.risk-monitor.max-price-age` : durÃ©e maximale depuis price_as_of, indÃ©pendante de display/execution-max-age ;
- `trading.risk-monitor.hysteresis-points` : Ã©cart positif de rÃ©armement ;
- `trading.risk-monitor.rearm-duration` : durÃ©e de rÃ©cupÃ©ration observÃ©e ;
- `trading.risk-monitor.max-observation-gap` : interruption maximale entre observations, au moins interval ;
- `trading.risk-monitor.batch-size` : taille de lot, entre 1 et 1000 ;
- `trading.risk-monitor.claim-lease`, `retry-initial`, `retry-max` : durÃ©es positives ; retry-max >= retry-initial ;
- `trading.risk-monitor.smtp.host`, `port`, `from`, `recipients` : serveur et adresses approuvÃ©s, liste des destinataires ;
- `trading.risk-monitor.smtp.username`, `password` : seulement si authentification nÃ©cessaire, via secrets externalisÃ©s ;
- `trading.risk-monitor.smtp.start-tls`, `ssl` : choix du transport, mutuellement exclusifs ;
- `trading.risk-monitor.smtp.timeout` : dÃ©lai connexion/lecture/Ã©criture, trois fois infÃ©rieur au bail.

Les durÃ©es doivent reprÃ©senter au moins une milliseconde. Aucune adresse SMTP nâ€™est codÃ©e dans les sources de production. Les valeurs des tests sont synthÃ©tiques et ne constituent pas des paramÃ¨tres de dÃ©ploiement.

## Prix et applicabilitÃ©

Le chemin de tarification existant est rÃ©utilisÃ© avec une limite spÃ©cifique. Prix absent, pÃ©rimÃ© ou futur : PRICE_STALE, alerte unique Ã  lâ€™entrÃ©e, aucune rÃ©pÃ©tition ni email de rÃ©cupÃ©ration. Un retour Ã  un calcul valide permet une nouvelle entrÃ©e ultÃ©rieure ; PRICE_STALE nâ€™efface jamais les alertes financiÃ¨res dÃ©cidÃ©es et interrompt leur durÃ©e de rÃ©armement.

Un taux absent produit MARGIN_RATE_MISSING auditÃ©, sans mail financier fabriquÃ©. `error_code` non nul invalide toute lecture du niveau et des indicateurs comme Ã©tat courant sain ; les derniÃ¨res valeurs valides restent historiques, datÃ©es par valid_calculated_at. Aucun calcul partiel nâ€™est publiÃ©.

Acquisition et calcul hors transaction. Dans une transaction courte (timeout 5 s), verrou compte puis Ã©tat, comparaison de version et empreinte des entrÃ©es (compte, soldes, paramÃ¨tres actifs). Toute observation devenue obsolÃ¨te est abandonnÃ©e ; elle sera recalculÃ©e au passage suivant. Lâ€™Ã¢ge du prix est revÃ©rifiÃ© avant persistance. Lâ€™empreinte ne contient pas les prix : une observation utilise les cotations quâ€™elle a rÃ©ellement acquises, sous rÃ©serve de fraÃ®cheur.

La rÃ©fÃ©rence price_as_of est le plus ancien timestamp des mÃ©taux utilisÃ©s. Le mÃ©canisme de pricing existant peut utiliser une heure dâ€™acquisition quand le fournisseur nâ€™en fournit pas : ce nâ€™est pas une certification de fraÃ®cheur du marchÃ© sous-jacent.

## Persistance et rÃ©armement

013 crÃ©e STATE et EVENT sans modifier 001..012. STATE porte le niveau, la version, les dates, la mÃ©moire de rÃ©armement et les derniers indicateurs valides. Les indicateurs numÃ©riques sont regroupÃ©s en JSONB : totalFunds, positionValuation, grossPosition, netEquity, marginRequirement, freeEquity, coverageCalculated, coverageDisplayed, currency.

EVENT fige lâ€™ancien/nouveau niveau, les indicateurs nÃ©cessaires Ã  lâ€™explication, la rÃ©fÃ©rence temporelle des prix et le motif. Aucun snapshot dÃ©taillÃ© de prix par mÃ©tal nâ€™est dupliquÃ©. Les snapshots historiques existants restent inchangÃ©s : ils ne conservent ni la prÃ©cision de dÃ©cision ni les nouveaux niveaux. Un trigger interdit la mutation du contenu dâ€™audit et sa suppression ; seules les mÃ©tadonnÃ©es de livraison Ã©voluent.

Un Ã©vÃ©nement en attente compte dÃ©jÃ  comme alerte dÃ©cidÃ©e. Un saut direct crÃ©e une seule alerte au niveau final et marque les niveaux infÃ©rieurs comme signalÃ©s. Pour chaque seuil T, rÃ©armement aprÃ¨s couverture strictement > T + hysteresis-points durant rearm-duration, sans trou supÃ©rieur Ã  max-observation-gap. Les rÃ©cupÃ©rations restent auditÃ©es sans email. NO_POSITION clÃ´t lâ€™Ã©pisode.

## SMTP et reprise

La dÃ©cision dâ€™alerte et lâ€™Ã©vÃ©nement sont atomiques. Lâ€™unicitÃ© compte/sÃ©quence et les verrous PostgreSQL empÃªchent deux dÃ©cisions concurrentes identiques.

Le worker rÃ©clame un Ã©vÃ©nement Ã  la fois via FOR UPDATE SKIP LOCKED et commit avant SMTP. Chaque claim possÃ¨de un token et un bail ; acquittement et retry exigent le token courant et un bail valide. Reprise exponentielle bornÃ©e ; erreurs enregistrÃ©es sous un code technique sans message SMTP brut. Un Message-ID stable dÃ©rive de lâ€™UUID de lâ€™Ã©vÃ©nement et du domaine de lâ€™expÃ©diteur.

V1 SMTP nâ€™est pas exactly-once : une rÃ©ponse perdue aprÃ¨s acceptation peut produire deux emails portant le mÃªme identifiant. Ce risque est explicitement acceptÃ©. Une alerte en retry peut Ãªtre livrÃ©e aprÃ¨s une alerte plus rÃ©cente ; le mail indique la date de la dÃ©cision. last_notified_sequence empÃªche un acquittement ancien de faire rÃ©gresser lâ€™Ã©tat notifiÃ©.

## Exploitation et retour

Activation, destinataires, TLS et frÃ©quence restent une dÃ©cision de dÃ©ploiement ultÃ©rieure. Le volume des comptes et les dÃ©lais SMTP doivent Ãªtre dimensionnÃ©s avant activation ; les jobs utilisent le scheduling Spring existant.

Retour opÃ©rationnel : dÃ©sactiver le monitor et son worker, conserver STATE/EVENT et les emails en attente. Pas de rollback destructif, purge ni suppression dâ€™audit. Aucun dÃ©ploiement ni SMTP externe nâ€™est validÃ© par les tests locaux.

## Validation

RÃ©sultats finaux Ã  consigner aprÃ¨s les cycles complets. Les tests PostgreSQL Risk Monitor crÃ©ent et vÃ©rifient leur propre schÃ©ma sur lâ€™instance locale historique, puis ne suppriment que ce schÃ©ma. Aucun Ã©vÃ©nement prÃ©existant nâ€™est rÃ©clamÃ©. Le test SMTP protocolaire utilise uniquement loopback.


### Preuves et commandes réellement exécutées

Java : JBR IntelliJ Java 21. Maven IntelliJ, dépôt local existant, mode offline, aucun profil Maven explicite.

```powershell
$env:JAVA_HOME='C:\Users\BOUZEBOUDJA\AppData\Local\Programs\IntelliJ IDEA Ultimate\jbr'
$M='C:\Users\BOUZEBOUDJA\AppData\Local\Programs\IntelliJ IDEA Ultimate\plugins\maven\lib\maven3\bin\mvn.cmd'
$R='C:\Users\BOUZEBOUDJA\.m2\repository'
& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=RiskCalculatorTest,RiskServiceTest' test
& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=RiskMonitorPolicyTest,RiskMonitorIntegrationTest,RiskCalculatorTest,RiskServiceTest,ReservationSemanticsMigrationTest' test
& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=RiskMonitorPolicyTest,RiskMonitorIntegrationTest,RiskMonitorConfigurationTest,RiskCalculatorTest,RiskServiceTest,ReservationSemanticsMigrationTest' test
& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=RiskMonitorIntegrationTest#freshnessIsCheckedAfterWaitingForAccountLock+twoInstancesDecideOneEventAndRejectObsoleteObservation+rearmIsPersistedAcrossServiceInstances,RiskMonitorPolicyTest' test
& $M -o -B "-Dmaven.repo.local=$R" '-Dtest=RiskMonitorConfigurationTest,RiskMonitorIntegrationTest#freshnessIsCheckedAfterWaitingForAccountLock' test
& $M -o -B "-Dmaven.repo.local=$R" clean test
& $M -o -B "-Dmaven.repo.local=$R" clean package
```

| Passage | Tests | Suites | Code | Failures/errors/skipped |
|---|---:|---:|---:|---|
| Risk historique après extraction | 7 | 2 | 0 | 0/0/0 |
| Premier ciblé monitor | 46 | 5 | 0 | 0/0/0 |
| Ciblé enrichi SMTP/réarmement | 50 | 6 | 0 | 0/0/0 |
| Correction fraîcheur après verrou | 22 | 2 | 0 | 0/0/0 |
| Horodatage d'audit / configuration | 4 | 2 | 0 | 0/0/0 |
| **clean test confirmé** | **397** | **41** | **0** | **0/0/0** |
| **clean package** | **397** | **41** | **0** | **0/0/0** |

Deux clean test intermédiaires ont également passé : 395 puis 396 tests, 41 suites, code 0, aucun échec/erreur/ignoré. Ils précèdent les dernières assertions/corrections et ne sont pas présentés comme preuve finale. Une première tentative de compilation en sandbox a échoué (accès refusé / fermeture des ressources du compilateur, code Maven 1), avant exécution des tests ; la relance autorisée avec le même Maven et le même dépôt a réussi. Aucun skip ou changement de destination n'a été utilisé.

Ces passages sont distincts : leurs nombres de tests ne s'additionnent pas comme tests uniques.

Rapports conservés dans `%TEMP%\saamp-risk-monitor-20260909-211717` : logs de chaque commande, targeted-1-reports, targeted-2-reports, freshness-targeted-reports, audit-time-targeted-reports, clean-test-intermediate-reports, clean-test-intermediate-2-reports, **clean-test-confirmed-reports**, **clean-package-reports**. Les rapports précédant ce chantier sont dans historical-reports. Les rapports de package restent aussi dans target/surefire-reports.

Présence vérifiée dans les deux cycles finaux : OrderRiskProjectionTest (17), OrderExecutionServiceTest (16), ExecutionEventHandlerTest (3), ReservationServiceTest (1), RuleBTradingIntegrationTest (62), ReservationSemanticsMigrationTest (4), TradingDatabaseInvariantTest (13), SimulatedTradingEndToEndTest (2), As400FilledIsolationIntegrationTest (1), OrderCapacityServiceTest (26). Les nouvelles suites sont RiskMonitorPolicyTest (19), RiskMonitorIntegrationTest (19), RiskMonitorConfigurationTest (3).

Les assertions couvrent : seuils avant arrondi, NO_POSITION, valorisations LONG/SHORT/multi-métal, somme des valeurs absolues, taux général/spécifique/absent, prix absent/périmé/futur, anti-spam, réarmement persisté, fermeture, comptes suspendus exposés, deux décisions concurrentes dont une seule validée, rollback état/événement, observation obsolète refusée, SKIP LOCKED, reprise après bail expiré et refus d'un acquittement ancien.

La vérification de fraîcheur utilise l'horloge **après acquisition du verrou compte** ; les fenêtres de paramètres sont relues avec clock_timestamp(). Le test bloque réellement ce verrou, avance uniquement son horloge de test, puis vérifie PRICE_STALE. L'événement d'audit est daté de cette décision après verrou, pas du calcul antérieur.

Le test SMTP protocolaire utilise JavaMailSenderImpl et un serveur loopback synthétique : première réponse perdue après réception, retry du même événement, Message-ID identique et état final SENT. Il démontre l'ambiguïté SMTP acceptée, pas une livraison exactly-once. Les tests avec double vérifient aussi l'absence de transaction active et la disponibilité du verrou compte pendant l'envoi.

Installation complète 001→013 par le master et montée 012→013 exécutées sur les schémas temporaires vérifiés. Historique 001..012 comparé avant/après ; contraintes, unicité et immutabilité d'audit vérifiées. La migration 013 est également appliquée par les contextes historiques de test sur la base locale autorisée ; aucune opération UAT/production.

WAR : `target/trading-api.war`, **48 378 615 octets**, **2026-09-09T22:13:33.9174631+02:00**, SHA-256 `3da35bbf345113ecbafc2cf2a129d9943faaefa73514166a1d0f2521f3bf145e`.

Migration 013 : SHA-256 `2c666c68226f7e7525241b9e8f9cc2c5c937072495476faa4b4e77c0500b34aa`.

Limites restantes : aucun SMTP externe/TLS réel testé ; activation et paramètres opérationnels à fournir séparément ; doublon exceptionnel après acceptation SMTP sans acquittement durable accepté en V1 ; charge/fréquence à dimensionner avant activation. Le monitor est désactivé par défaut. Aucun commit, push ou déploiement effectué.


### Corrections ciblées après revue finale — 9 septembre 2026

Seuls les seuils configurables, le bail SMTP et le plafond des tentatives sont corrigés dans cette passe. Les résultats 397/41 ci-dessus restent ceux de l'état antérieur.

- `trading.risk-monitor.warning-threshold` : défaut métier approuvé 105.
- `trading.risk-monitor.critical-threshold` : défaut métier approuvé 104.
- `trading.risk-monitor.liquidation-required-threshold` : défaut métier approuvé 102.
- Ordre strict requis : liquidation < critical < warning. Classification et réarmement utilisent ces mêmes propriétés, sans changement de formule ni d'arrondi.
- `trading.risk-monitor.max-attempts` : défaut **5**, plafond technique V1 (pas une règle métier), minimum 1. Une tentative est comptée lors du claim, même si le processus disparaît avant l'envoi.

Pendant SMTP, un heartbeat renouvelle le bail toutes les `claim-lease / 3`, via une transaction PostgreSQL courte ciblant l'identifiant et le token courant. Un bail expiré ou repris ne peut pas être ressuscité. SMTP reste hors transaction. Le contrôle de propriété en mémoire ne remplace jamais la condition SQL ; ack et retry restent conditionnels. En cas de perte de propriété, aucun ack/retry de l'ancien worker n'est autorisé.

À l'épuisement, le même EVENT passe à FAILED, conserve son contenu d'audit, porte SMTP_ATTEMPTS_EXHAUSTED, sans token, bail, date d'envoi ni prochaine tentative. Il est exclu des claims automatiques. Un dernier claim abandonné devient FAILED après expiration, sans tentative supplémentaire. Les états terminaux ne peuvent pas être réouverts par UPDATE.

013 est ajustée avec l'autorisation explicite de cette passe ; 001..012 et le master restent inchangés. Attention : la version précédente de 013 avait déjà été appliquée sur la base locale des suites historiques. Aucun clearCheckSums, modification de DATABASECHANGELOG ou nettoyage de cette base n'est autorisé pour masquer ce conflit. Les tests propres à 013 utilisent leurs schémas temporaires vérifiés.


#### Résultats de cette passe — validation complète bloquée

Maven IntelliJ / JBR Java 21, `-o -B -Dmaven.repo.local=C:\Users\BOUZEBOUDJA\.m2\repository`, aucun profil explicite. Les cycles complets ne comportent ni sélection, ni exclusion, ni skip.

| Passage | Suites | Tests comptabilisés | Failures | Errors | Skipped | Code |
|---|---:|---:|---:|---:|---:|---:|
| Première tentative sandbox, compilation bloquée | 0 | 0 | — | — | — | 1 |
| Reproductions sur code antérieur | 2 | 3 | 3 | 0 | 0 | 1 |
| Sélection ciblée corrigée | 6 | 63 | 0 | 0 | 0 | 0 |
| clean test final | 41 | 396 | 0 | 66 | 0 | 1 |
| clean package final | 41 | 396 | 0 | 66 | 0 | 1 |

Sélection ciblée : RiskMonitorPolicyTest, RiskMonitorConfigurationTest, RiskMonitorIntegrationTest, ReservationSemanticsMigrationTest, RiskCalculatorTest, RiskServiceTest. Compteurs respectifs : 20, 9, 23, 4, 1, 6. Les trois reproductions démontrent le binding absent, le retry sans fin après cinq tentatives et la reprise par un second worker pendant un SMTP loopback lent ; elles passent après correction.

Les deux cycles complets ont été effectivement exécutés après le dernier changement technique. Les 52 tests des trois suites Risk Monitor passent dans chacun. Quatre initialisations sont bloquées par la validation de 013, puis 62 cas reçoivent le refus de réinitialisation Spring. Répartition des erreurs : RuleBTradingIntegrationTest 62, SimulatedTradingEndToEndTest 2, As400FilledIsolationIntegrationTest 1, TradingDatabaseInvariantTest 1. Dans cette dernière suite, l'initialisation de classe échoue avant les 13 scénarios ; le compteur XML est donc réduit. Ces erreurs ne constituent pas une validation des scénarios métier correspondants.

Conflit exact : `db/changelog/013-risk-monitor.sql::013-risk-monitor::saamp`, checksum local `9:f863a3f29ff3da3bfc4d22a75d325334`, checksum corrigé `9:7332a830dff7f2bcb114f59d31564537`. Aucun clearCheckSums, modification de DATABASECHANGELOG, suppression de tables locales ou contournement n'a été effectué. Une décision distincte sur l'environnement de validation déjà migré est nécessaire avant de pouvoir obtenir les deux cycles verts.

Les tests de migration sur schémas temporaires vérifiés passent : installation complète 001→013 et upgrade 012→013 avec historique préservé. Le test SMTP lent dure au-delà du bail initial ; le token est conservé, le bail renouvelé, le second worker n'envoie rien et le premier acquitte l'unique tentative. Mauvais token, ancien token, bail expiré, reprise d'un claim abandonné et épuisement de la dernière tentative sont vérifiés. FAILED conserve le payload, interdit la réouverture et n'est plus claimé.

Rapports conservés sous `%TEMP%\saamp-risk-monitor-fixes-20260909-230255` : red-reports, targeted-reports (six suites du passage), clean-test-reports, clean-package-reports et logs associés. Les anciens résultats restent séparés. Les entrées techniques ont gardé l'empreinte `ab995f1d173da0a304ea9a4f8e787136a48ea7e2b422cf5322f8d770b1b34620` pendant les deux cycles.

Aucun nouveau WAR : le packaging s'arrête sur les tests. L'ancien WAR est conservé dans ce répertoire sous `previous-trading-api.war` (48 378 615 octets, SHA-256 `3da35bbf345113ecbafc2cf2a129d9943faaefa73514166a1d0f2521f3bf145e`) ; il ne contient pas ces corrections. Aucun commit, push, déploiement, accès UAT/production ou SMTP externe.


### Garantie de livraison email V1

Livraison email at-least-once.
Une alerte peut exceptionnellement être envoyée plusieurs fois lorsqu'une
livraison SMTP réussit sans que son acquittement puisse être persisté
durablement avant expiration du bail. Le système privilégie la répétition
possible d'une alerte à sa perte.

Cela couvre la perte de réponse SMTP et un succès SMTP explicite suivi d'une
indisponibilité PostgreSQL empêchant l'acquittement durable. Le Message-ID reste
stable ; aucune garantie exactly-once. Les reprises restent soumises au plafond
max-attempts : FAILED conserve l'alerte et son audit sans garantir sa livraison.
Une exception de heartbeat signifie UNKNOWN, et les renouvellements continuent ;
seul un refus PostgreSQL signifie LOST. Après succès SMTP, l'acquittement reste
conditionnel au token et au bail, même après UNKNOWN.
