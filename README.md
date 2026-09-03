# SAAMP MyTrading — Trading API

Java 21 / Spring Boot 3.5 / PostgreSQL / Liquibase.

Cette base implémente le socle de la spécification **MyTrading Lot 2 — Trading API v1.0** avec la règle de couverture **A**.

## Ce qui est déjà codé

- compte Trading par `company_id`, EUR/USD ;
- soldes unifiés devise + XAU/XAG/XPT/XPD ;
- protection PostgreSQL interdisant tout solde devise négatif ;
- ledger append-only et projection des soldes ;
- transferts externes idempotents par `external_ref` ;
- réservations persistantes avec expiration, sans transaction longue ;
- taux de marge par défaut 5/7/12/12/3 % ;
- prix de marché horodatés et contrôle de fraîcheur ;
- spread BUY/SELL versionné par société et actif ;
- arrondi favorable SAAMP ;
- API client ne renvoyant jamais le Bid/Ask StoneX brut ;
- conversion g/kg/oz vers l'once troy canonique ;
- `idempotency_key` + `ClOrdId` déterministe ;
- contrôle pré-ordre BUY / SELL couvert + découvert ;
- `deal_limit` et `position_limit` ;
- réservation BUY au pire cas (`1 + drift_tolerance`) ;
- contrôle `PRICE_MOVED` avant l'appel irréversible au fournisseur ;
- interface `TradingProvider` indépendante de PMX/FIX ;
- provider simulé pour développer et tester sans StoneX ;
- état `PENDING_UNKNOWN` et résolution planifiée sans retransmission ;
- backoff 2 s / 5 s / 15 s / 30 s / 60 s, puis revue manuelle après 15 min ;
- calcul StoneX-compatible : Net Equity, Margin Requirement, Free Equity, Position, Coverage % ;
- fixture de test du relevé StoneX du 13/08/2026 ;
- validation JWT MyPortal RS256 par JWKS localement mis en cache ;
- rafraîchissement JWKS horaire et limitation à un refresh/5 min sur `kid` inconnu ;
- contrôle des permissions `MYTRADING_*` et isolation par `companyId` ;
- blocage immédiat via `trading_account.status` ;
- Swagger / OpenAPI via Springdoc ;
- Actuator health.

## PMXConnect live — état v0.3

Les appels réels du 20/08/2026 ont permis de brancher en lecture :

- `GetSpotRates/MTL` (métaux) ;
- `GetSpotRates/FOR` (FX), fusionné avec MTL avec un `price_as_of` commun ;
- `GetCmdtyPositions`, avec persistance du `AccountCode` StoneX distinct du `ClientId` du TokenID ;
- `GetRequestStatus`, dont le cas `400 / error_code=631` est traité comme un rejet définitif sans retransmission.

Le mapper PMXConnect est insensible à la casse et force les nombres flottants en `BigDecimal`. `MMM` n'est volontairement pas mappé.

**Seul `/Trade` reste bloqué** jusqu'à obtention d'un TokenID UAT et observation des payloads succès / `InProcess`. Aucun appel de découverte n'est effectué avec le jeton de production.

Le mode par défaut est :

```yaml
trading:
  provider:
    mode: SIMULATED
```

Pour le live, une fois l'adaptateur terminé :

```text
TRADING_PROVIDER_MODE=PMXCONNECT
PMXCONNECT_BASE_URL=https://pmxconnect.stonex.com
PMXCONNECT_TOKEN_ID=<secret>
PMXCONNECT_VERSION=v1_3
PMXCONNECT_ENVIRONMENT=prod
```

Le `TokenID` ne doit jamais être commité ni journalisé.

## Build

```bash
mvn clean package
```

WAR :

```text
target/trading-api.war
```

## PostgreSQL local

```bash
docker compose up -d
mvn spring-boot:run
```

Valeurs locales par défaut :

```text
DB       trading
User     trading
Password trading
Port     5433
```

En environnement réel, définir :

```text
TRADING_DB_URL
TRADING_DB_USERNAME
TRADING_DB_PASSWORD
MYPORTAL_JWT_ISSUER
MYPORTAL_JWKS_URI
```

## Routes client V1

Toutes les ressources clientes restent sous `/accounts/me/...`.

```text
GET  /trading-api/api/v1/accounts/me/summary
GET  /trading-api/api/v1/accounts/me/prices
GET  /trading-api/api/v1/accounts/me/risk
GET  /trading-api/api/v1/accounts/me/ledger
GET  /trading-api/api/v1/accounts/me/orders
POST /trading-api/api/v1/accounts/me/orders/preview
POST /trading-api/api/v1/accounts/me/orders/{orderId}/submit
```

Le JWT attendu est celui émis par MyPortal avec :

```text
iss = https://myportal.saamp.com
aud = MYTRADING
companyId
permissions[]
```

## Préparation d'un ordre

Exemple :

```json
{
  "asset": "XAU",
  "side": "BUY",
  "quantity": 100,
  "unit": "G",
  "idempotencyKey": "4f62ab22-uat-0001"
}
```

`preview` :

1. retrouve le compte à partir de `companyId` ;
2. vérifie le statut du compte ;
3. convertit en oz ;
4. vérifie quantité / deal limit / position limit ;
5. calcule le prix client ;
6. effectue le contrôle de capacité ;
7. crée des réservations persistantes ;
8. retourne uniquement le prix client indicatif.

`submit` :

1. vérifie que la réservation est toujours active ;
2. récupère un prix frais ;
3. compare la dérive au prix présenté ;
4. refuse avec `PRICE_MOVED` au-delà de la tolérance ;
5. soumet via `TradingProvider` ;
6. ne retransmet jamais un ordre incertain ;
7. en cas de fill, comptabilise les deux jambes dans une seule transaction DB.

## Initialisation minimale pour un test local

Avant de tester les routes, créer un compte et un spread dans PostgreSQL :

```sql
INSERT INTO trading_account(company_id, base_currency)
VALUES (57, 'EUR');

INSERT INTO trading_spread(company_id, asset, spread_buy, spread_sell, config_version)
VALUES
  (57, 'XAU', 0.003000, 0.003000, 1),
  (57, 'XAG', 0.003000, 0.003000, 1),
  (57, 'XPT', 0.003000, 0.003000, 1),
  (57, 'XPD', 0.003000, 0.003000, 1);
```

Pour alimenter le compte en développement, utiliser `TransferService` (le transport service-to-service n'est volontairement pas exposé tant que son authentification n'est pas arbitrée).

Pour le provider simulé, insérer des prix :

```sql
INSERT INTO trading_market_price(pair,bid,ask,mid,price_as_of,source)
VALUES ('XAUEUR', 2900.000000, 2901.000000, 2900.500000, NOW(), 'SIMULATED')
ON CONFLICT (pair) DO UPDATE
SET bid=EXCLUDED.bid, ask=EXCLUDED.ask, mid=EXCLUDED.mid, price_as_of=NOW(), source='SIMULATED';
```

## Tests importants

`RiskCalculatorTest` reproduit le jeu StoneX de référence :

```text
Position Valuation   2 067 235,73
Net Equity           4 416 155,70
Margin Requirement  3 511 236,50
Free Equity            904 919,20
Position            58 483 534,78
Coverage %                 107,55
```

## Points à finir avant production

1. obtenir un TokenID UAT et finaliser `/Trade` avec des payloads observés ;
2. confirmer le comportement d'un éventuel fill partiel ;
3. valider la limite de débit de `GetSpotRates` et les horaires de séance ;
4. confirmer la durée de rétention des `ClOrdId` pour borner la règle `631` ;
5. brancher la synchronisation MyPortal → Trading API des comptes/spreads ;
6. définir l'authentification du processus externe d'ingestion des transferts ;
7. brancher/activer la réconciliation générique sur le compte/sous-compte StoneX dédié ;
8. ajouter le rendu PDF du relevé Account Dashboard (le dataset JSON commun est déjà présent) ;
9. traiter explicitement le cas extrême où le fill réel après `/Trade` excède la réservation au pire cas malgré le contrôle de dérive ;
10. faire valider le montage par le juridique avant mise en production client.

## Choix volontaire

Le code ne crée pas de contrôleur back-office par `accountId` avec le JWT client MyTrading. Les endpoints clients sont exclusivement `/accounts/me/...` afin d'éviter une fuite inter-sociétés par IDOR.

## Validation v0.3 — PMXConnect

La v0.2 intègre les corrections issues de la revue du Lot 2 v0.1 : arrondi financier StoneX au centime, prix d'exécution frais pour `position_limit`, et tests des invariants qui protègent les soldes et les ordres.

Les tests PostgreSQL utilisent Testcontainers et sont automatiquement ignorés sur un poste sans moteur Docker. En CI ou sur un poste avec Docker, ils appliquent réellement les changelogs Liquibase et vérifient les deux déclencheurs critiques.

```bash
mvn clean test
mvn clean package
```


### Corrections PMXConnect v0.3

- casse `Pair/Ask/Bid` et `PAIR/ASK/BID` gérée par mapper dédié ;
- `USE_BIG_DECIMAL_FOR_FLOATS` activé ;
- lecture MTL + FOR et stockage du snapshot fusionné ;
- paires construites sans séparateur (`XAUEUR`, `EURUSD`) ;
- positions `AccountCode/Cmdty/Position` mappées sur les payloads réels ;
- `AccountCode` provider persisté séparément du `ClientId` TokenID ;
- cas `631` clos automatiquement comme `REJECTED` ;
- `401/403` ferment l'execution gate ;
- environnement et expiration du TokenID contrôlés au démarrage du provider ;
- `/Trade` demeure volontairement désactivé jusqu'au TokenID UAT.
