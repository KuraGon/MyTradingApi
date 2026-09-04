# Contrat HTTP V1 — Front MyTrading

Ce document décrit uniquement le contrat effectivement exposé par l'API au 4 septembre 2026. Les URI ci-dessous sont relatives au contexte applicatif `/trading-api` ; par exemple, l'URL déployée du compte courant se termine par `/trading-api/api/v1/accounts/me`.

## Conventions communes

- Authentification : JWT MyPortal dans l'en-tête `Authorization: Bearer …`. Aucun jeton ne doit être placé dans l'URI, le corps ou les journaux du front.
- Toutes les permissions indiquées sont cumulatives. `MYTRADING_ACCESS` est toujours requis.
- Corps JSON : `Content-Type: application/json` pour les requêtes avec corps.
- Erreurs fonctionnelles : `Content-Type: application/problem+json`.
- Nombres financiers : nombres JSON issus de `BigDecimal`. Le front doit utiliser une représentation décimale, jamais un calcul binaire en `number` pour une décision financière.
- Dates : chaînes ISO 8601 en UTC ou avec décalage, selon le DTO (`Instant` ou `OffsetDateTime`).
- Valeurs métal : `XAU`, `XAG`, `XPT`, `XPD`. Devise du compte actuellement exposée : `EUR` ou `USD`.
- Les exemples montrent la forme des DTO, sans promettre les valeurs illustrées. Les champs nuls des réponses d'ordre peuvent être sérialisés à `null`.

Forme d'une erreur fonctionnelle :

```json
{
  "type": "urn:saamp:trading:error:price_moved",
  "title": "PRICE_MOVED",
  "status": 409,
  "detail": "Le prix a évolué au-delà de la tolérance; nouvelle cotation requise",
  "instance": "/api/v1/accounts/me/orders/71/submit",
  "code": "PRICE_MOVED"
}
```

Le front doit piloter son comportement avec `status` et `code`, pas avec le texte de `detail`.

## Routes front V1

| Méthode | URI | Permissions |
|---|---|---|
| `GET` | `/api/v1/accounts/me` | `MYTRADING_ACCESS` + `MYTRADING_ACCOUNT_READ` |
| `GET` | `/api/v1/accounts/me/balances` | `MYTRADING_ACCESS` + `MYTRADING_ACCOUNT_READ` |
| `GET` | `/api/v1/accounts/me/positions` | `MYTRADING_ACCESS` + `MYTRADING_ACCOUNT_READ` |
| `GET` | `/api/v1/accounts/me/summary` | `MYTRADING_ACCESS` + `MYTRADING_ACCOUNT_READ` |
| `GET` | `/api/v1/accounts/me/prices` | `MYTRADING_ACCESS` + `MYTRADING_ACCOUNT_READ` |
| `GET` | `/api/v1/accounts/me/orders` | `MYTRADING_ACCESS` + `MYTRADING_HISTORY_READ` |
| `GET` | `/api/v1/accounts/me/statement` | `MYTRADING_ACCESS` + `MYTRADING_HISTORY_READ` |
| `POST` | `/api/v1/accounts/me/orders/preview` | `MYTRADING_ACCESS` + `MYTRADING_ORDER_WRITE` |
| `POST` | `/api/v1/accounts/me/orders/{orderId}/submit` | `MYTRADING_ACCESS` + `MYTRADING_ORDER_WRITE` |

Il n'existe pas de route publique `/accounts/me/ledger`, `/accounts/me/risk`, ni de route de consultation d'un ordre isolé.

## Consultation du compte

### `GET /api/v1/accounts/me`

Paramètres : aucun. Corps de requête : aucun.

Réponse `200 OK` :

```json
{
  "id": 10,
  "baseCurrency": "EUR",
  "status": "ACTIVE",
  "createdAt": "2026-09-01T08:00:00Z",
  "updatedAt": "2026-09-03T10:00:00Z"
}
```

`status` vaut `ACTIVE`, `RISK_RESTRICTED`, `SUSPENDED` ou `CLOSED`.

Erreurs possibles : `401`, `403`, `404 TRADING_ACCOUNT_NOT_FOUND`.

Action front : initialiser le contexte du compte. Autoriser l'accès en lecture quel que soit le statut ; ne proposer le workflow d'ordre que pour `ACTIVE`.

### `GET /api/v1/accounts/me/balances`

Paramètres : aucun. Corps de requête : aucun.

Réponse `200 OK` :

```json
[
  {
    "asset": "EUR",
    "balance": 12500.000000,
    "available": 10500.000000,
    "updatedAt": "2026-09-03T10:00:00Z"
  },
  {
    "asset": "XAU",
    "balance": 2.000000,
    "available": 1.500000,
    "updatedAt": "2026-09-03T10:00:00Z"
  }
]
```

La collection contient la devise de base et les métaux présents dans les soldes ; une autre devise est filtrée. `available` est net des réservations actives.

Erreurs possibles : `401`, `403`, `404 TRADING_ACCOUNT_NOT_FOUND`.

Action front : afficher `balance` comme solde comptable et `available` comme montant réellement disponible. Ne pas recalculer le disponible côté front.

### `GET /api/v1/accounts/me/positions`

Paramètres : aucun. Corps de requête : aucun.

Réponse `200 OK` :

```json
[
  {
    "asset": "XAU",
    "quantityOz": 2.000000,
    "clientPrice": 2000.00,
    "valuation": 4000.00,
    "priceAsOf": "2026-09-03T10:00:00Z"
  }
]
```

Erreurs possibles : `401`, `403`, `404 TRADING_ACCOUNT_NOT_FOUND`, `409 MARKET_PRICE_STALE`, `409 SPREAD_NOT_CONFIGURED`, `409 ASSET_NOT_CONFIGURED`, `409 ASSET_DISABLED`, `503 MARKET_PRICE_MISSING`.

Action front : afficher la valorisation publiée par l'API et son horodatage. Ne pas reconstruire `valuation` à partir du prix. En cas de prix absent ou périmé, conserver la dernière vue connue si disponible et signaler que la valorisation ne peut pas être actualisée.

### `GET /api/v1/accounts/me/summary`

Paramètres : aucun. Corps de requête : aucun.

Réponse `200 OK` :

```json
{
  "accountId": 10,
  "baseCurrency": "EUR",
  "status": "ACTIVE",
  "dealLimit": 50000.00,
  "positionLimit": 100000.00,
  "risk": {
    "totalFunds": 1000.00,
    "positionValuation": 200.00,
    "netEquity": 1200.00,
    "marginRequirement": 20.00,
    "freeEquity": 1180.00,
    "grossPosition": 200.00,
    "coveragePct": 700.00,
    "riskStatus": "NORMAL"
  }
}
```

`dealLimit` et `positionLimit` peuvent être `null`. `riskStatus` vaut `NORMAL`, `WARNING`, `CRITICAL`, `NO_POSITION` ou `PRICE_STALE`.

Erreurs possibles : `401`, `403`, `404 TRADING_ACCOUNT_NOT_FOUND`, ainsi que les erreurs de prix/configuration nécessaires au calcul : `409 MARKET_PRICE_STALE`, `409 SPREAD_NOT_CONFIGURED`, `409 ASSET_NOT_CONFIGURED`, `409 ASSET_DISABLED`, `503 MARKET_PRICE_MISSING`.

Action front : utiliser directement les agrégats publiés. En particulier, ne pas recalculer `netEquity`, `freeEquity` ou `marginRequirement` à partir d'autres réponses.

## Prix client

### `GET /api/v1/accounts/me/prices`

Paramètre query optionnel `assets`, répétable. Sans paramètre, l'API demande les quatre métaux. Exemples valides : `?assets=XAU` ou `?assets=XAU&assets=XAG`. Les actifs non métalliques valides (`EUR`, `USD`) sont ignorés ; une valeur inconnue provoque un `400` de conversion de requête.

Corps de requête : aucun.

Réponse `200 OK` :

```json
[
  {
    "asset": "XAU",
    "pair": "XAUEUR",
    "buyPrice": 2010.00,
    "sellPrice": 1990.00,
    "priceAsOf": "2026-09-03T10:00:00Z"
  }
]
```

Erreurs possibles : `400` paramètre invalide, `401`, `403`, `404 TRADING_ACCOUNT_NOT_FOUND`, `409 MARKET_PRICE_STALE`, `409 SPREAD_NOT_CONFIGURED`, `409 ASSET_NOT_CONFIGURED`, `409 ASSET_DISABLED`, `503 MARKET_PRICE_MISSING`.

Action front : afficher `buyPrice` pour un achat et `sellPrice` pour une vente avec `priceAsOf`. Ces prix sont indicatifs ; seul un preview crée le brouillon et les réservations nécessaires.

## Workflow d'ordre SPOT

```text
PREVIEW → affichage du prix → validation explicite utilisateur → SUBMIT → résultat
```

Le front génère une clé d'idempotence stable pour une intention de preview et la conserve localement pour ses éventuelles répétitions réseau. Il appelle `preview`, affiche les données retournées, obtient une validation explicite de l'utilisateur, puis appelle une seule fois `submit` avec `orderId`. Une nouvelle intention ou une nouvelle cotation reçoit une nouvelle clé.

### `POST /api/v1/accounts/me/orders/preview`

Paramètres URI/query : aucun.

Requête JSON :

```json
{
  "asset": "XAU",
  "side": "BUY",
  "quantity": 1.000000,
  "unit": "OZ",
  "idempotencyKey": "client-generated-key-maximum-64-characters"
}
```

Contraintes réelles : tous les champs sont obligatoires ; `quantity >= 0.000001` ; `idempotencyKey` non vide et de 64 caractères maximum. `asset` accepte l'enum `EUR`, `USD`, `XAU`, `XAG`, `XPT`, `XPD`, mais seuls les métaux configurés peuvent aboutir. `side` vaut `BUY` ou `SELL`. `unit` vaut `G`, `KG` ou `OZ`.

Réponse `200 OK` :

```json
{
  "orderId": 71,
  "asset": "XAU",
  "side": "BUY",
  "quantityOz": 1.000000,
  "pair": "XAUEUR",
  "indicativeClientPrice": 2010.00,
  "priceAsOf": "2026-09-03T10:00:00Z",
  "expiresAt": "2026-09-03T10:02:00Z",
  "reservedCash": 2014.020000,
  "reservedMetal": 0
}
```

Erreurs possibles : `400 VALIDATION_ERROR`, `400 QUANTITY_TOO_SMALL`, `400` JSON/enum invalide, `401`, `403`, `404 TRADING_ACCOUNT_NOT_FOUND`, `409 ACCOUNT_NOT_ACTIVE`, `409 INSUFFICIENT_AVAILABLE_BALANCE`, `409 DEAL_LIMIT_EXCEEDED`, `409 POSITION_LIMIT_EXCEEDED`, `409 MARGIN_RATE_MISSING`, `409 MARKET_PRICE_STALE`, `409 SPREAD_NOT_CONFIGURED`, `409 ASSET_NOT_CONFIGURED`, `409 ASSET_DISABLED`, `503 MARKET_PRICE_MISSING`.

`expiresAt` est la plus proche des échéances réellement persistées pour les réservations de l'ordre. Il ne doit pas être déduit de `priceAsOf`.

Action front : afficher `indicativeClientPrice`, `quantityOz`, les réservations et `priceAsOf`, puis demander une validation explicite. Le countdown utilise exclusivement `expiresAt`. Lorsque `expiresAt` est dépassé, désactiver le bouton Submit et demander un nouveau preview. Le serveur reste la source de vérité et peut répondre `RESERVATION_EXPIRED`, notamment si l'horloge ou le timer du front dérive. Ne jamais traiter le preview comme une exécution. Ne jamais afficher ni attendre la clé d'idempotence dans la réponse : elle n'y figure volontairement pas.

### `POST /api/v1/accounts/me/orders/{orderId}/submit`

Paramètre path `orderId` : entier retourné par le preview. Corps de requête : aucun.

Réponse `200 OK` :

```json
{
  "id": 71,
  "asset": "XAU",
  "pair": "XAUEUR",
  "side": "BUY",
  "orderType": "SPOT",
  "requestedQuantity": 1.000000,
  "requestedUnit": "OZ",
  "quantityOz": 1.000000,
  "status": "FILLED",
  "indicativeClientPrice": 2010.00,
  "clientPrice": 2011.00,
  "grossAmount": 2011.00,
  "createdAt": "2026-09-03T10:00:00Z",
  "submittedAt": "2026-09-03T10:00:08Z",
  "executedAt": "2026-09-03T10:00:09Z"
}
```

`clientPrice`, `grossAmount`, `submittedAt` et `executedAt` peuvent être `null` selon l'état. `status` appartient à `DRAFT`, `VALIDATED`, `PENDING`, `PENDING_UNKNOWN`, `FILLED`, `REJECTED`, `EXPIRED`, `FAILED`.

Erreurs possibles : `401`, `403`, `404 ORDER_NOT_FOUND`, `404 TRADING_ACCOUNT_NOT_FOUND`, `409 ACCOUNT_NOT_ACTIVE`, `409 ORDER_NOT_SUBMITTABLE`, `409 RESERVATION_EXPIRED`, `409 PRICE_MOVED`, `409 MARKET_PRICE_STALE`, `503 MARKET_PRICE_MISSING`, `503 EXECUTION_BLOCKED`, `503 PROVIDER_EXECUTION_NOT_READY`.

Action front selon le résultat :

- `FILLED` : ordre exécuté ; afficher le prix client et le montant brut retournés, puis rafraîchir soldes, positions, synthèse, ordres et relevé.
- `REJECTED` : ordre refusé ; ne pas annoncer une exécution et rafraîchir l'historique.
- `PRICE_MOVED` est une erreur HTTP `409`, pas un statut d'ordre : ne pas soumettre automatiquement. Demander une nouvelle cotation via un nouveau preview et une nouvelle validation utilisateur.
- `PENDING_UNKNOWN` : ne jamais afficher « ordre échoué ». Afficher exactement le sens « Execution pending confirmation ». Ne jamais retransmettre automatiquement. Rafraîchir `GET /api/v1/accounts/me/orders` et rechercher le même `id` jusqu'à obtention d'un état terminal.
- `PENDING` : transmission en cours ; même discipline de non-retransmission et de rafraîchissement que pour `PENDING_UNKNOWN`.

### `GET /api/v1/accounts/me/orders`

Paramètre query optionnel `limit` : entier, `100` par défaut, ramené dans l'intervalle `1..500`. Corps de requête : aucun.

Réponse `200 OK` : tableau d'objets ayant exactement la même forme que la réponse de submit ci-dessus, trié du plus récent au plus ancien.

Erreurs possibles : `400` si `limit` n'est pas un entier, `401`, `403`, `404 TRADING_ACCOUNT_NOT_FOUND`.

Action front : afficher l'historique et utiliser `id` pour suivre un ordre `PENDING` ou `PENDING_UNKNOWN`. Aucun endpoint de détail unitaire n'existe en V1.

## Relevé

### `GET /api/v1/accounts/me/statement`

Paramètres query optionnels :

- `cursor` : identifiant de ligne exclusif ; absent pour la première page ;
- `limit` : entier, `100` par défaut, ramené dans l'intervalle `1..500`.

Corps de requête : aucun.

Réponse `200 OK` :

```json
{
  "accountId": 10,
  "baseCurrency": "EUR",
  "generatedAt": "2026-09-03T10:00:00Z",
  "lines": [
    {
      "id": 101,
      "asset": "EUR",
      "delta": -2011.00,
      "entryType": "TRADE",
      "orderId": 71,
      "balanceAfter": 10489.00,
      "createdAt": "2026-09-03T10:00:09Z"
    }
  ],
  "nextCursor": 101,
  "hasMore": true
}
```

`entryType` vaut `TRANSFER_IN`, `TRANSFER_OUT`, `TRADE`, `FEE` ou `ADJUSTMENT`. `orderId` et `nextCursor` peuvent être `null`. Pour charger la page suivante, réutiliser `nextCursor` comme `cursor` uniquement lorsque `hasMore` vaut `true`.

Erreurs possibles : `400` si `cursor` ou `limit` n'est pas un entier, `401`, `403`, `404 TRADING_ACCOUNT_NOT_FOUND`.

Action front : afficher les lignes dans l'ordre reçu et paginer avec le curseur fourni. Ne pas dériver le relevé depuis les soldes ou l'historique des ordres.

## Erreurs à gérer côté front

| Code / cas | HTTP | Signification | Action front |
|---|---:|---|---|
| `MARKET_PRICE_STALE` | `409` | Le prix disponible dépasse l'âge maximal accepté. | Bloquer la décision concernée, signaler une cotation indisponible et permettre un rafraîchissement explicite. |
| `PRICE_MOVED` | `409` | Entre preview et submit, le prix client a dépassé la tolérance. L'ordre concerné est rejeté. | Ne jamais resoumettre. Créer un nouveau preview et demander une nouvelle validation. |
| `INSUFFICIENT_AVAILABLE_BALANCE` | `409` | Le solde net des réservations ne couvre pas la réservation demandée. | Afficher l'insuffisance, rafraîchir les soldes et laisser l'utilisateur modifier son ordre. |
| `TRADING_ACCOUNT_NOT_FOUND` | `404` | Aucun compte n'est visible pour la société authentifiée. | Afficher un état « compte indisponible » sans tenter un autre identifiant. |
| `ACCOUNT_NOT_ACTIVE` | `409` | Le compte est `SUSPENDED`, `RISK_RESTRICTED` ou `CLOSED`; preview/submit est interdit. | Désactiver la saisie/soumission et rafraîchir `/accounts/me`. Pour `SUSPENDED`, afficher que le trading est suspendu. |
| `VALIDATION_ERROR` | `400` | Une contrainte Jakarta Validation du JSON de preview échoue. | Associer `detail` au formulaire, corriger la saisie, ne pas soumettre. |
| JSON, enum ou paramètre invalide | `400` | Désérialisation ou conversion impossible. Aucun code métier stable supplémentaire n'est défini par le projet pour ce cas. | Corriger la requête ; ne pas boucler automatiquement. |
| authentification absente/invalide | `401` | Le JWT est absent, expiré, invalide ou son contexte client est inutilisable. Le service possède aussi les codes internes `UNAUTHENTICATED` et `INVALID_TOKEN_SCOPE` lorsqu'ils atteignent le handler. | Relancer le flux d'authentification. Ne jamais journaliser le jeton. |
| permission absente | `403` | Le JWT est valide mais ne porte pas toutes les permissions de la route. | Masquer l'action concernée ; ne pas réessayer automatiquement. |
| ressource invisible | `404` | Compte ou ordre absent, y compris ressource appartenant à une autre société. | Afficher « introuvable » sans révéler ni rechercher une autre société. |
| `PENDING_UNKNOWN` | `200`, champ `status` | État d'exécution indéterminé ; ce n'est pas une erreur HTTP ni un échec confirmé. | Afficher « Execution pending confirmation », ne jamais retransmettre, rafraîchir l'historique. |

Autres codes réellement émis sur ces chemins : `ASSET_NOT_CONFIGURED` (`409`), `ASSET_DISABLED` (`409`), `QUANTITY_TOO_SMALL` (`400`), `DEAL_LIMIT_EXCEEDED` (`409`), `POSITION_LIMIT_EXCEEDED` (`409`), `MARGIN_RATE_MISSING` (`409`), `SPREAD_NOT_CONFIGURED` (`409`), `MARKET_PRICE_MISSING` (`503`), `ORDER_NOT_FOUND` (`404`), `ORDER_NOT_SUBMITTABLE` (`409`), `RESERVATION_EXPIRED` (`409`), `EXECUTION_BLOCKED` (`503`), `PROVIDER_EXECUTION_NOT_READY` (`503`) et `DATA_INTEGRITY_VIOLATION` (`409`).

## Matrice écrans

| Écran | Endpoint | Champs utilisés | Disponible | Manquant |
|---|---|---|---|---|
| Positions by Metal | `GET /positions` | `asset`, `quantityOz`, `clientPrice`, `valuation`, `priceAsOf` | Oui | Unité d'affichage utilisateur et quantité convertie |
| Summary | `GET /summary` | `baseCurrency`, `status`, `dealLimit`, `positionLimit`, objet `risk` | Oui | Rien pour les agrégats demandés |
| Net Equity | `GET /summary` | `risk.netEquity`, `baseCurrency` | Oui | Rien |
| Free Equity | `GET /summary` | `risk.freeEquity`, `baseCurrency` | Oui | Rien |
| Margin Requirement | `GET /summary` | `risk.marginRequirement`, `baseCurrency` | Oui | Rien |
| Order Preview | `POST /orders/preview` | `orderId`, `asset`, `side`, `quantityOz`, `pair`, `indicativeClientPrice`, `priceAsOf`, `expiresAt`, `reservedCash`, `reservedMetal` | Oui | Quantité et unité initialement saisies à conserver côté front |
| Confirmation / Submit | `POST /orders/{orderId}/submit` | tous les champs de `OrderView`, surtout `status`, `clientPrice`, `grossAmount` | Oui | Motif client structuré lorsque `status=REJECTED` |
| PRICE_MOVED | réponse d'erreur du submit, puis nouveau `POST /preview` | `code`, `status`, `detail` | Partiel | Nouvelle cotation non incluse dans l'erreur |
| PENDING_UNKNOWN | submit puis `GET /orders` | `id`, `status`, horodatages | Partiel | Endpoint de détail d'un ordre ; état de suivi/résolution plus précis |
| Orders History | `GET /orders` | tableau de `OrderView` | Partiel | Pagination/curseur et motif client structuré d'un rejet |
| Statement | `GET /statement` | `accountId`, `baseCurrency`, `generatedAt`, `lines`, `nextCursor`, `hasMore` | Oui | Libellé client localisé de `entryType` (peut être mappé par le front) |

Les abréviations `/positions`, `/summary`, `/orders` et `/statement` dans cette matrice désignent leurs URI complètes sous `/api/v1/accounts/me`.

## DONNÉES FRONT MANQUANTES

Le contrat actuel ne fournit pas les données suivantes ; elles ne sont pas ajoutées dans cette version documentaire :

- préférence d'unité utilisateur et quantité de position convertie en `G` ou `KG` ; seule `quantityOz` est publiée ;
- motif client structuré d'un ordre retourné avec `status: REJECTED` ; les erreurs fournisseur internes restent volontairement non exposées ;
- nouvelle cotation dans la réponse `PRICE_MOVED` ; un nouveau preview est obligatoire ;
- endpoint de détail d'un ordre et métadonnées de progression de la résolution `PENDING_UNKNOWN` ; le seul rafraîchissement disponible est l'historique récent ;
- pagination de l'historique des ordres au-delà des 500 plus récents.

## OpenAPI

Springdoc/OpenAPI est présent. Sous le contexte `/trading-api` :

- description JSON : `/v3/api-docs` ;
- Swagger UI : `/swagger-ui.html`.

La configuration OpenAPI V1 déclare l'authentification HTTP Bearer JWT. Les routes Springdoc sont publiquement accessibles, mais les opérations métier restent protégées par les permissions indiquées dans ce document.

## Champs volontairement absents du contrat public

Aucune réponse V1 ci-dessus n'expose `ClOrdId`, `idempotencyKey`, une écriture `LedgerEntry` brute, un `TradingOrder` brut, un `RiskResult` brut, un identifiant d'exécution fournisseur, `createdBy`, `transferRef`, un payload/une erreur fournisseur brute, un prix StoneX brut, `spreadApplied`, `saampRevenue` ou un jeton.
