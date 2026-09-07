# Alimentation AS400 — MyTrading V1

## Source de vérité et périmètre

PostgreSQL et `trading_ledger_entry` restent la vérité temps réel. La transaction existante conserve le ledger, les balances, le passage FILLED et la création outbox. Aucun accès AS400 n'est réalisé dans cette transaction. Une panne AS400 ne modifie ni FILLED, ni le règlement devise publié à deux décimales HALF_UP, ni StoneX.

Un ordre FILLED produit **un seul événement SICOUVI et un seul mouvement client** dans `SPECIF1.SICOUVI1`. Aucune contrepartie historique n'est créée. Aucune écriture directe n'est faite dans `CLCPDP03` ou `PCGMLFCM` : le traitement AS400 alimente les poids et la comptabilité.

Le mapping existant `trading_account.as400_ste` / `as400_nucli_trading` est réutilisé. STE est conservé sans transformation de casse. Le NUCLI commercial ne sert jamais de repli ; la contrainte existante impose deux NUCLI distincts lorsqu'ils sont renseignés. STE et NUCLI trading sont figés dans l'outbox à sa création pour conserver la corrélation après une modification du compte. Un mapping absent produit FAILED lors du traitement, sans bloquer FILLED.

## Outbox et migration 009

La migration `009-as400-sicouvi.sql` fait évoluer la table existante, sans modifier 001–008. La contrainte unique existante `(order_id,target)` garantit un événement SICOUVI par ordre et fournit déjà l'index sur order_id.

Elle ajoute :
- `sync_state`, progression durable indépendante du statut technique PROCESSING ;
- `sicoui`, `siprov`, `as400_ste`, `nucli_trading` ;
- `submitted_at`, `accepted_at`, `settled_at`, `last_checked_at` ;
- les contraintes de bornes et de cohérence des étapes ;
- les index SICOUI, SIPROV et l'index partiel des événements dus ;
- `trading_as400_sicoui_seq`, bornée 1..999999, cyclique, non unique par elle-même.

SICOUI est alloué et persisté lors de la création de l'événement, dans la transaction PostgreSQL existante. Un retry ou reclaim ne réalloue jamais ce numéro. Les trous de séquence et son cycle sont acceptés : la corrélation complète contient également STE, SICLI et la référence d'ordre.

Les anciens événements WEIGHT_ACCOUNT / ACCOUNTING non SYNCED passent en BLOCKED côté status technique avec `AS400_LEGACY_TARGET_REQUIRES_MANUAL_REVIEW`. Leur sync_state et leurs autres données historiques sont conservés. Les anciens SYNCED sont conservés. Toutes les anciennes cibles sont exclues du claim, quel que soit leur statut. Elles ne sont jamais converties automatiquement en SICOUVI.

**Déploiement : arrêter/désactiver les anciens workers avant la migration et leur remplacement.** Un ancien binaire encore actif conserverait son ancien comportement. Tout rattrapage historique nécessite une analyse opérateur des écritures déjà réalisées ; ne pas créer massivement de nouveaux événements pour les anciens ordres.

## Mapping SICOUVI1

| Champ | Valeur |
|---|---|
| SISTE | STE figé du compte trading |
| SICOUI | Séquence PostgreSQL persistée, 1..999999 |
| SIPROV | 0 à l'insertion |
| SICLI | NUCLI trading figé du client uniquement |
| SIDTEC / SIDEAC | Date de `trading_order.executed_at`, YYMMDD, Europe/Paris |
| SIHEUC | Heure de cette exécution, HHMMSS, Europe/Paris |
| SIACFV | BUY client → V ; SELL client → A |
| SIMET | XAU → O ; XAG → A ; XPT → P ; XPD → D |
| SICND | OR SPOT / ARGENT SPOT / PT SPOT / PD SPOT, ≤ 15 caractères |
| SIPDS | `quantity_oz × 31.1034768`, scale 2 HALF_UP |
| SITXCH | EUR : 1.00000 |
| SITYCO | 20 |
| SICOT | `round(client_price × 1000 / (31.10348 × SITXCH), 0)`, stocké à scale 4 |
| SIREF1 | MYTRADING |
| SIRELO | Blanc |
| SIREF2 | OR SPOT / ARGENT SPO / PT SPOT / PD SPOT, ≤ 10 caractères |
| SIREF3 | MT- suivi de orderId en base36 majuscule ; ≤ 16 caractères pour un BIGINT positif |
| SIREF4 | 0 |
| CREDAT / CREHEU | Instant de la tentative de création AS400, YYMMDD / HHMMSS, Europe/Paris |
| MAJDAT / MAJHEU | 0 |

Les calculs utilisent exclusivement BigDecimal. La division de SICOT est effectuée une seule fois, à la précision finale, pour éviter un arrondi intermédiaire arbitraire. Le prix est `trading_order.client_price`, pas `market_price`. La devise vient de la paire persistée de l'ordre exécuté. Exemple : 57.41702408 EUR/oz donne SICOT 1846.0000.

Un poids arrondi à 0.00 produit `AS400_WEIGHT_ROUNDS_TO_ZERO`, sans INSERT. Les dépassements DECIMAL et métaux non pris en charge sont également refusés explicitement. Aucun arrondi artificiel à 0.01 et aucune troncature des libellés.

Aucune source FX AS400 officielle exploitable n'a été identifiée dans le backend. USD produit `AS400_FX_RATE_UNAVAILABLE` et FAILED côté sync uniquement. L'utilisation des prix FOR de StoneX comme taux comptable AS400 n'est pas présumée valide.

## Workflow et reprise

```text
FILLED → PENDING → SUBMITTED → ACCEPTED → SETTLED
             claim technique PROCESSING à chaque contrôle
```

- PENDING : lecture de corrélation avant INSERT. Une ligne déjà présente est réutilisée.
- SUBMITTED : SICOUVI confirmé, SIPROV = 0. Contrôle par défaut toutes les 5 minutes.
- ACCEPTED : SIPROV > 0, numéro et accepted_at persistés. Contrôle par défaut toutes les heures.
- SETTLED : une ligne `GESCOMF.PROVISP1` correspond à `STE + NUPROV + NUCLI` et `ETPRO1 = 'O'`.
- FAILED : donnée impossible, mapping absent, USD sans taux officiel ou ambiguïté de corrélation.

Un SIPROV déjà positif à la première confirmation permet d'enregistrer directement ACCEPTED, avec submitted_at et accepted_at. Un provisoire absent ou ETPRO1 blanc conserve ACCEPTED. ETPRO2 n'est jamais lu. Une disparition de SICOUVI après SUBMITTED est signalée et réessayée ; aucun nouvel INSERT n'est effectué.

Après un contrôle réussi, status revient à PENDING pour SUBMITTED/ACCEPTED ; il passe à SYNCED pour SETTLED. Une donnée impossible donne status=BLOCKED et sync_state=FAILED. Le claim ne modifie jamais sync_state. Seuls les status PENDING/RETRY/PROCESSING avec une progression PENDING/SUBMITTED/ACCEPTED sont éligibles au claim SICOUVI.

Les erreurs temporaires donnent status=RETRY, conservent sync_state et les horodatages acquis, incrémentent attempt_count, enregistrent un code d'erreur sans secret et utilisent le backoff existant (15 secondes, exponentiel, plafond 1 heure). Aucun nombre maximal de tentatives ne transforme une simple indisponibilité en FAILED. last_error est effacé après un contrôle réussi.

Le claim conserve UUID, `FOR UPDATE SKIP LOCKED`, bail de 5 minutes et reclaim. Avant le traitement, le token est revérifié sous verrou PostgreSQL de la ligne outbox, dans une transaction propre au worker. Ce verrou empêche un second worker de réclamer un événement encore activement traité après expiration de son bail. Il ne verrouille ni ordre, ni ledger, ni balance. Les écritures de progression et d'erreur restent conditionnées au token propriétaire. Un crash libère la connexion et permet la reprise du bail.

Avant chaque INSERT, la lecture utilise `SISTE + SICOUI + SICLI + SIREF3`. Après une exception JDBC de l'INSERT, une seconde lecture tente de confirmer son acceptation. Si elle échoue ou ne retrouve aucune ligne, le traitement reste retryable. Une corrélation ambiguë est signalée, jamais choisie arbitrairement. Aucune transaction distribuée, aucun XA/2PC.

La recherche d'absence et l'INSERT sont exécutés dans une transaction **locale DB2 SERIALIZABLE**, avec un délai de 30 secondes. Le read-back après échec d'INSERT ou de COMMIT utilise une nouvelle transaction : une ligne non commitée ne vaut pas confirmation. Cette protection reste côté destinataire si le verrou PostgreSQL disparaît pendant un appel. Les polls utilisent aussi cette transaction courte pour ne publier que des données confirmées.

Sur IBM i, JDBC SERIALIZABLE correspond à COMMIT(*RR), qui protège les lectures par des verrous de table : voir les [niveaux JDBC IBM i](https://www.ibm.com/docs/en/i/7.6.0?topic=transactions-transaction-isolation-levels) et [COMMIT(*RR)](https://www.ibm.com/docs/en/i/7.5?topic=level-repeatable-read). **Avant activation réelle, vérifier la journalisation/commitment control des fichiers, les droits et la contention avec le batch.** Si cette transaction n'est pas disponible, la synchronisation échoue explicitement et reste retryable ; aucun repli vers une insertion non protégée n'est prévu. Le gestionnaire DB2 est privé à l'adaptateur : il ne remplace pas le gestionnaire PostgreSQL et ne crée aucun XA/2PC.

## Configuration

| Propriété / variable | Défaut / rôle |
|---|---|
| trading.as400.enabled / TRADING_AS400_ENABLED | false ; les événements continuent d'être créés |
| trading.as400.sync-delay / TRADING_AS400_SYNC_DELAY | 30s ; scan PostgreSQL uniquement |
| trading.as400.submitted-poll-delay / TRADING_AS400_SUBMITTED_POLL_DELAY | 5m |
| trading.as400.accepted-poll-delay / TRADING_AS400_ACCEPTED_POLL_DELAY | 1h |
| trading.as400.jdbc-url / TRADING_AS400_JDBC_URL | À fournir uniquement pour un AS400 configuré |
| trading.as400.username / TRADING_AS400_USERNAME | Obligatoire si URL fournie |
| trading.as400.password / TRADING_AS400_PASSWORD | Obligatoire si URL fournie, sans valeur de repli |

L'absence d'URL laisse l'UAT démarrer sans datasource AS400. Si le worker est activé sans URL, il conserve les événements en retry avec AS400_NOT_CONFIGURED. Les délais doivent être strictement positifs. JdbcTemplate impose un délai maximal de requête de 30 secondes ; les délais de connexion/réseau doivent également être configurés dans l'environnement JTOpen.

Le driver runtime `net.sf.jt400:jt400:21.0.6` fournit `com.ibm.as400.access.AS400JDBCDriver` dans le WAR. Le JAR standard JTOpen est compatible Java 21 (bytecode Java 8), conformément au [projet IBM JTOpen](https://github.com/IBM/JTOpen). Aucune connexion réseau n'est ouverte par la configuration au démarrage. Le JdbcTemplate principal reste celui de PostgreSQL, y compris quand le second JdbcTemplate AS400 existe.

`TRADING_PROVIDER_MODE=SIMULATED` reste inchangé. Aucun endpoint public de replay ni dépendance auth supplémentaire n'est ajouté. Une correction manuelle/reprise d'un FAILED nécessite de vérifier son identité et sa présence AS400 avant toute action. Ne jamais réallouer SICOUI sur un retry.

## Consultations et réconciliation conservées

La lecture de `GESCOMF.CLCPDP03` reste inchangée : STE, NUCLI, NULIV = 0 et circuits O/A/P/D ; SOLD03 en grammes, converti en oz par 31.1034768. Elle n'intervient pas dans SETTLED.

La lecture devise de `FMPRO.PCGMLFCM` reste inchangée : somme signée L1MTT, jointure PARSOCP1, comptes 401600/411600, L1ETA=1 et L1TYP=1. Elle reste informative ; aucune écriture comptable directe n'est conservée.
