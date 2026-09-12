# DEMO : surface autorisee et provisionnement

Le mode est fixe sur l'ordre a sa creation et ne peut plus changer. Les historiques
restent LIVE. DEMO utilise exclusivement `trading_demo_balance` et
`trading_demo_ledger_entry`, y compris sous EffectiveBalance SHADOW/ENFORCED.
DEV/UAT/PROD et LIVE/DEMO sont deux dimensions distinctes. PROD peut autoriser
`TRADING_DEMO_ENABLED=true`. Le token exige `identityType=INTERNAL`,
`accessMode=INTERNAL_DEMO`, `tradingMode=DEMO` et un compte `account_mode=DEMO`.
CLIENT utilise `CLIENT_SELF` + LIVE et un compte LIVE. Les anciens tokens CLIENT
sans accessMode restent compatibles ; aucun ancien token DEMO implicite n'est accepte.

`TradingExecutionProviderRouter` route LIVE vers le fournisseur configure et DEMO
vers un `SimulatedTradingProvider` local, meme si le fournisseur LIVE est PMXConnect.
La cotation reste partagee : `MarketDataRefreshService` peut lire StoneX SPC pour
les deux modes. `/Trade` ne recoit jamais DEMO, avec un second refus explicite au
boundary PMX. Le gate persistant controle LIVE ; une fermeture du gate LIVE ne
bloque pas la simulation, qui conserve Rule B et ses reservations DEMO.

## Surface HTTP complete

Les dix routes authentifiees actuelles sont aussi l'allowlist DEMO ci-dessous.
Le prefixe de servlet `/trading-api` precede ces chemins lors du deploiement.
Les permissions MyTrading et les controles company/order restent obligatoires.

| Methode | Chemin |
| --- | --- |
| GET | /api/v1/accounts/me |
| GET | /api/v1/accounts/me/balances |
| GET | /api/v1/accounts/me/positions |
| GET | /api/v1/accounts/me/summary |
| GET | /api/v1/accounts/me/prices |
| GET | /api/v1/accounts/me/orders |
| GET | /api/v1/accounts/me/orders/{orderId numerique} |
| GET | /api/v1/accounts/me/statement |
| POST | /api/v1/accounts/me/orders/preview |
| POST | /api/v1/accounts/me/orders/{orderId numerique}/submit |

`DemoRouteAuthorization` refuse toute autre route authentifiee pour DEMO, y compris
si une route future est ajoutee. Aucun controleur actuel n'expose transferts,
configuration de compte/mapping, commandes admin, ingestion, reconciliation,
provider ou maintenance. Leur ajout ne les autorisera pas implicitement en DEMO.
Les routes publiques health/documentation et le preflight CORS restent publics.

## Provisionner ou remettre a niveau la demonstration (NON EXECUTE)

Script prepare : `scripts/uat/demo-account-reset.sql`. Exclusivement `trading_uat`.
L'operateur fournit `account_id`, `EUR`, `USD`, `XAU`, `XAG`, `XPT`, `XPD` et
`reset_by` (son identite d'audit). Aucune valeur financiere par defaut.
Les valeurs representent les soldes cibles, pas des deltas ; metaux en onces troy.
Six decimales au maximum. Authentification PostgreSQL via le mecanisme securise
habituel, jamais via une valeur de mot de passe sur la ligne de commande.

Invocation a preparer avec des variables operateur deja renseignees :

```bash
psql -X -d trading_uat -v ON_ERROR_STOP=1 \
  -v account_id="$DEMO_ACCOUNT_ID" -v reset_by="$DEMO_RESET_BY" \
  -v EUR="$DEMO_EUR" -v USD="$DEMO_USD" -v XAU="$DEMO_XAU" \
  -v XAG="$DEMO_XAG" -v XPT="$DEMO_XPT" -v XPD="$DEMO_XPD" \
  -f scripts/uat/demo-account-reset.sql
```

Ne pas utiliser `set -x`, `psql -a/-e`, ni enregistrer les montants operateur.
Le script verifie la base, verrouille le compte dans une transaction, refuse les
ordres DEMO DRAFT/PENDING/PENDING_UNKNOWN et reservations DEMO ACTIVE, puis ajuste
la seule projection DEMO. Six ecritures `DEMO_ADJUSTMENT` conservent les deltas,
soldes apres, operateur, date et reference transactionnelle du reset. Un reset
identique produit encore une trace de delta zero. Aucun historique n'est efface.
Seul un resume account_id/nombre d'actifs/reference/utilisateur DB est affiche.
Le script n'a ete execute sur aucune base, y compris locale.

## Jobs sans JWT

| Job | Isolation |
| --- | --- |
| PendingUnknownResolver | SQL `trading_mode='LIVE'` pour PENDING et PENDING_UNKNOWN ; seconde barriere Java avant toute interrogation provider. DEMO inconnu reste a resoudre manuellement, sans StoneX. |
| ReconciliationService | Agrege seulement `BalanceRepository` / `trading_balance` LIVE ; aucun acces au ledger/projection DEMO. |
| RiskMonitorService | Candidats, fingerprint et Risk sur projection LIVE/EffectiveBalance LIVE ; aucun ordre DEMO selectionne. |
| RiskMonitorMailWorker | Delivre uniquement les evenements du monitor LIVE. |
| As400SyncWorker | Enqueue/claim limites LIVE, trigger DB anti-DEMO. Aucun groupe DEMO ne peut etre cree. |
| ReservationService expiry | Expire les DRAFT des deux modes localement ; aucun appel provider/AS400. |
| PmxTokenExpiryMonitor | Metadonnees du token configure ; aucun ordre consulte. |
| RateLimitedJwksJwtDecoder | Rafraichissement des cles publiques ; aucun ordre consulte. |
| ShadowBalanceDiagnostics | Diagnostic uniquement LIVE ; les chemins DEMO retournent avant toute acquisition officielle. |

Aucun autre scheduler de provider n'existe. Le changement de provider au redemarrage
ne rend pas resolubles par StoneX les anciens ordres DEMO inconnus.

## Invariants SQL / migration 015

Ordres historiques LIVE ; mode ordre immuable. Les reservations referencees doivent
avoir le meme account_id/mode que leur ordre, a l'insertion comme a la modification.
Le ledger DEMO TRADE exige un ordre DEMO du meme compte ; le ledger LIVE refuse les
ordres DEMO et le type DEMO_ADJUSTMENT. Les deux ledgers sont append-only.
DEMO_ADJUSTMENT n'a pas d'ordre, porte une reference d'audit et reste uniquement DEMO.
L'overlay et l'outbox ne selectionnent que LIVE. Le workflow SICOUVI LIVE est intact.

Le rollback automatique de 015 refuse des qu'une donnee DEMO existe : supprimer le
mode transformerait autrement des historiques DEMO en LIVE. Apres utilisation,
archivage et rollback DB requierent une revue manuelle ; le rollback WAR seul ne
restaure pas le modele de donnees. Les migrations 001..014 ne sont pas modifiees.

## Comptes dedies et provisioning PROD (non execute)

Provisionner une PortalCompany SAAMP DEMO `[A PROVISIONNER]`, puis un nouveau
trading_account dont company_id correspond et account_mode vaut DEMO des l'insertion.
Ne pas convertir un compte LIVE : account_mode est immutable. Les historiques sont LIVE.
Ne pas reutiliser la company UAT 1 ou une vraie societe cliente en PROD.
Les colonnes AS400 peuvent rester NULL pour DEMO ; les contraintes LIVE historiques
restent intactes et LIVE ENFORCED exige toujours un mapping trading exploitable.
Configurer explicitement devise, spreads, taux de marge et limites de demonstration.
Aucun compte, identifiant ou montant PROD n'est cree par la migration.

Le script `scripts/demo/account-reset.sql` est prepare pour les exploitants autorises.
Fournir les memes huit parametres que le script UAT, plus `expected_database` (base
exactement visee) et `apply_demo_reset=YES`. Il refuse sous verrou un compte non DEMO,
les engagements actifs et une base differente. Aucun montant par defaut. Il ecrit
seulement les six soldes DEMO et six DEMO_ADJUSTMENT append-only traces par operateur
et reference de transaction. Ne pas journaliser les montants en terminal. Non execute.

La DB relie ordre/reservation au couple (account_id, account_mode) par FK composite.
Elle interdit aussi toute projection, tout ledger ou transfert LIVE sur compte DEMO,
et toute projection/ledger DEMO sur compte LIVE, meme sans ordre associe.

## V2 preparee, non activee

TradingAccessMode distingue CLIENT_SELF, INTERNAL_DEMO et INTERNAL_DELEGATED.
INTERNAL_DELEGATED est refuse en V1 ; aucune permission de delegation n'est creee.
Une future liste de contextes apres login proposera SAAMP Demo et les seules societes
autorisees explicitement au salarie. Une selection produira un token pour un seul
accessMode, tradingMode et companyId. Le sub reste le vrai userId du salarie.
La delegation LIVE exigera une nouvelle permission dediee et un entitlement
utilisateur/societe ; jamais une permission globale toutes societes.
