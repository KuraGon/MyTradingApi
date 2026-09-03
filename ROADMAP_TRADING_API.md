# Trading API — Feuille de route vers une V1 fonctionnelle

**Version :** 1.0
**Date :** 3 septembre 2026
**Destinataire :** Codex, agent de développement
**À lire avec :** `AGENTS.md`, `SPEC_MYTRADING_LOT2_TRADING_API.md`, `SPEC_ALIMENTATION_AS400.md`, `CORRECTIONS_PMXCONNECT.md`

---

## 0. Comment utiliser ce document

Il décrit **l'ordre dans lequel amener la Trading API à un état fonctionnel**, et rien d'autre. Chaque étape est une tâche autonome, avec ce qu'il faut faire, ce qu'il ne faut pas toucher, et comment savoir qu'elle est finie.

**Ne saute pas d'étape et n'en anticipe pas une suivante.** L'ordre n'est pas arbitraire : il sécurise d'abord ce qui garantit, avant d'ajouter ce qui manipule de l'argent.

À la fin de chaque étape, rends compte : fichiers modifiés, résumé de chaque modification, nombre de tests exécutés avant et après, et tout point resté non vérifiable.

---

## 1. Où en est le projet

### Déjà livré et validé

**Lot 1 — Authentification.** En production. MyPortal émet des JWT RS256, la Trading API les valide localement via JWKS. Second facteur obligatoire, droits par société.

**Lot 2 — Socle.** Le projet démarre, les quatre changesets Liquibase s'appliquent, 24 tests unitaires passent. Sont en place : comptes et soldes, ledger en ajout seul, réservations, règle A, service de prix, spread, contrôle de dérive, `PENDING_UNKNOWN`, calcul de risque conforme au relevé StoneX.

Les deux déclencheurs PostgreSQL ont été vérifiés manuellement : non-négativité devise, et ledger en ajout seul.

### Ce qui manque pour une V1 fonctionnelle

| # | Manque | Bloqué par |
|---|---|---|
| 1 | Les tests d'invariants base ne s'exécutent pas | rien |
| 2 | Lecture des soldes AS400 | rien |
| 3 | Endpoints de consultation et de transfert | rien |
| 4 | Écritures de transfert dans le ledger | rien |
| 5 | Scénario complet de bout en bout | étapes 1 à 4 |
| 6 | Adaptateur `/Trade` réel | jeton UAT StoneX |

Les étapes 1 à 5 ne dépendent de personne. **Elles peuvent être menées immédiatement.**

---

## 2. Étape 1 — Sécuriser les tests d'invariants

**Priorité : première. Aucune autre étape ne commence avant.**

### Le problème

`TradingDatabaseInvariantTest` utilise Testcontainers. Sans Docker actif, les cinq tests sont **ignorés silencieusement** et le build reste vert. Ils ne sont même pas comptés en `skipped` — ils disparaissent.

Or ce sont eux qui vérifient la non-négativité devise, l'ajout seul du ledger, la concurrence des réservations et l'application des changelogs. Autrement dit, toutes les garanties portées par la base.

Trois défauts ont déjà franchi un build vert : deux erreurs de compilation, et un `splitStatements` manquant qui empêchait tout démarrage.

### À faire

Rendre ces tests exécutables contre une PostgreSQL locale :

```
host      localhost
port      5432
database  trading
user      trading
password  trading
```

**Et les faire échouer explicitement** si aucune base n'est joignable. Jamais un `skip` silencieux.

### Piège connu

`application.yml` a pour défaut le port **5433**, alors que la base locale écoute sur **5432**.

```yaml
url: ${TRADING_DB_URL:jdbc:postgresql://localhost:5433/trading}
```

Corrige ce défaut ou rends-le explicite. Sans cela, les tests échoueront pour une raison qui n'a rien à voir avec le sujet.

### Interdits

- Ne rends aucun test moins strict pour obtenir un build vert.
- Ne modifie aucun invariant métier.
- Ne modifie aucun changeset Liquibase déjà appliqué.
- Ne refactorise rien qui ne soit pas demandé.

### Terminé quand

`mvn clean test` exécute **29 tests**, dont les 5 de `TradingDatabaseInvariantTest`, sans Docker. Et l'arrêt de PostgreSQL fait échouer le build avec un message clair.

---

## 3. Étape 2 — Lecture des soldes AS400

**Référence :** `SPEC_ALIMENTATION_AS400.md`

### À faire

Implémenter `As400AccountReader` : lecture seule, deux méthodes.

```java
/**
 * Lecture des soldes détenus par un client sur les comptes SAAMP historiques.
 *
 * <p>Ces soldes sont informatifs : ils ne participent jamais au calcul de la capacité
 * d'engagement, laquelle repose exclusivement sur les soldes du compte de trading.</p>
 */
public interface As400AccountReader {

    /** Soldes métal convertis en onces troy. Seuls O, A, P, D sont retournés. */
    Map<Asset, BigDecimal> readMetalBalances(String ste, int nucli);

    /** Solde devise des comptes de trading 401600 et 411600. */
    BigDecimal readTradingCurrencyBalance(String ste, int nucli);
}
```

Les requêtes exactes sont dans la spec, §3 et §4. Elles ont été vérifiées sur l'AS400.

### Contrainte absolue

> **Aucune méthode de cette interface ne doit être appelable depuis le passage d'ordre.**

Un appel AS400 sur le chemin critique introduirait une dépendance de disponibilité et une latence imprévisible sur une opération financière irréversible. Cette lecture ne sert qu'au back-office et à la préparation des transferts.

### Points de vigilance

`CIRCUI` est un `CHAR(3)` : la valeur revient en `"O  "`. **Appliquer un `trim()`** avant toute comparaison.

Seules les lignes `NULIV = 0` sont retenues. Pas d'agrégation.

Les soldes négatifs sont **normaux** — ce sont des reliquats d'affinage. Ne les rejette pas à la lecture. En revanche, un solde négatif ne constitue **pas** une capacité transférable.

`SOLD03` est en grammes. Conversion en onces troy : division par `31,1034768`.

Les règles AS400 du §5 de `AGENTS.md` s'appliquent intégralement : fichiers physiques, pas de `ROW_NUMBER()`, pas de commentaires de bloc, pas d'accents dans les alias, connexion en lecture seule.

### Tests attendus

Sans AS400 accessible en test, l'implémentation doit être testable par injection : un `As400AccountReader` de test retournant des valeurs connues, plus des tests unitaires sur la conversion et le filtrage des métaux hors périmètre.

### Terminé quand

L'interface est implémentée, testée par injection, et il est **impossible** de l'appeler depuis `OrderExecutionService` — vérifie-le et dis comment tu l'as garanti.

---

## 4. Étape 3 — Endpoints de consultation

### À faire

Exposer ce dont le front a besoin pour afficher un compte.

| Méthode | Chemin | Permission |
|---|---|---|
| `GET` | `/accounts/me` | `MYTRADING_ACCOUNT_READ` |
| `GET` | `/accounts/me/balances` | `MYTRADING_ACCOUNT_READ` |
| `GET` | `/accounts/me/positions` | `MYTRADING_ACCOUNT_READ` |
| `GET` | `/accounts/me/summary` | `MYTRADING_ACCOUNT_READ` |
| `GET` | `/accounts/me/orders` | `MYTRADING_HISTORY_READ` |
| `GET` | `/accounts/me/statement` | `MYTRADING_HISTORY_READ` |

Le `summary` reprend les indicateurs StoneX : fonds totaux, valorisation des positions, valeur nette, marge, capacité disponible, taux de couverture, exposition, plafond.

### Règles

**`/accounts/me` exclusivement.** Aucun endpoint client paramétré par identifiant. Les endpoints `{id}` sont réservés au back-office, avec un rôle distinct.

**Le périmètre du jeton est informatif.** Vérifie systématiquement que la ressource appartient à la société de l'appelant.

**Une ressource d'une autre société renvoie `404`, jamais `403`.**

**Le prix StoneX brut ne sort jamais.** Uniquement des prix client-ajustés. Ni `market_price`, ni `spread_applied`, ni `saamp_revenue` dans une réponse client.

**Prix périmé** : `MARKET_PRICE_STALE` plutôt qu'une valorisation silencieusement fausse.

### Terminé quand

Les endpoints répondent, l'isolation par société est testée, et aucun champ interne ne fuit — écris un test qui le vérifie explicitement.

---

## 5. Étape 4 — Transferts

**Référence :** `SPEC_MYTRADING_LOT2_TRADING_API.md` §9

### À faire

L'ingestion des transferts. Le mouvement est **déclenché par un processus externe** : la Trading API l'enregistre, elle ne l'initie pas.

```
POST /admin/transfers
{
  "accountId": 42,
  "asset": "XAU",
  "quantity": 321.507,
  "direction": "IN",
  "externalRef": "AS400-2026-09-03-0001"
}
```

### Règles

**Idempotence sur `externalRef`.** Un rejeu ne crée jamais de double écriture. C'est la garantie principale.

**Un `TRANSFER_OUT` qui rendrait un solde devise négatif est rejeté**, jamais appliqué partiellement.

**Prix d'acquisition figé à l'écriture**, au cours du jour du transfert, pour permettre un calcul de plus ou moins-value cohérent.

Deux écritures ledger par transfert, dans la même transaction. Le solde n'est jamais écrit directement.

### Contrôle de cohérence

Avant d'appliquer un `TRANSFER_IN`, vérifie via `As400AccountReader` que le solde AS400 couvre le montant. Un solde insuffisant ou négatif refuse le transfert.

C'est un contrôle de **préparation**, pas un contrôle d'ordre : il tourne hors du chemin critique.

### Terminé quand

Un transfert entrant crédite le compte, un rejeu ne fait rien, un transfert sortant excessif est refusé, et le tout est couvert par des tests.

---

## 6. Étape 5 — Scénario complet

### À faire

Un test d'intégration qui déroule le parcours entier, avec `SimulatedTradingProvider` :

```
1.  créer une société et un compte de trading
2.  transférer 100 000 EUR et 100 oz XAU
3.  vérifier les soldes et le summary
4.  passer un ordre d'achat dans la capacité       → accepté
5.  passer un ordre d'achat au-delà                 → refusé
6.  vendre une quantité couverte par le métal       → accepté, aucune réservation cash
7.  vendre au-delà du métal détenu                  → réservation cash avec marge
8.  vendre bien au-delà de la capacité              → refusé
9.  simuler une dérive de prix à la confirmation    → PRICE_MOVED, aucun appel fournisseur
10. simuler une exception fournisseur               → PENDING_UNKNOWN, aucune retransmission
11. résoudre en Processed                           → FILLED, écritures passées une seule fois
12. résoudre en 631                                 → REJECTED, réservations libérées
13. vérifier la cohérence finale : soldes = somme du ledger
```

L'étape 13 est la plus importante : elle vérifie que le ledger et les soldes ne peuvent pas diverger.

### Terminé quand

Le scénario passe intégralement et la cohérence finale est vérifiée.

---

## 7. Étape 6 — Adaptateur `/Trade`

**Bloqué. Ne pas commencer.**

`PmxConnectTradingProvider.submitSpotOrder()` lève volontairement une `UnsupportedOperationException`. Les payloads officiels de `/Trade` ne sont pas connus, et le seul jeton disponible porte `env: prod` — un appel passerait un ordre réel.

> **N'invente pas ces payloads.** Un échec bruyant vaut mieux qu'un mappage deviné qui échouerait en production.

En attente d'un `TokenID` UAT auprès de StoneX. Dès qu'il sera disponible, les réponses réelles seront capturées et le mappage écrit à partir de faits.

Tout le reste se teste avec `SimulatedTradingProvider`.

---

## 8. Ce qui n'est pas dans la V1

À ne pas anticiper, même si le modèle les prévoit :

- **ordres sur fixing** — la colonne `order_type` existe, seule la valeur `SPOT` est autorisée
- **ordres à seuil**
- **opérations à terme**
- **réconciliation automatique** — manuelle en V1, faute de sous-compte StoneX dédié
- **encours accordé par la direction** — `creditAllowance` reste à `0`, en attente d'arbitrage
- **rendu PDF du relevé** — le jeu de données structuré suffit
- **application mobile native** — la V1 est un site mobile

---

## 9. Rappels permanents

Ces règles s'appliquent à chaque étape. Le détail est dans `AGENTS.md`.

**Compile et teste avant de rendre.** Vérifie le **nombre de tests exécutés**, pas seulement le statut du build.

**Écris un test pour chaque invariant que tu touches.**

**Ne réécris pas ce qui fonctionne.** Modifications ciblées uniquement.

**Signale plutôt que deviner.** Si une information manque, dis-le et propose un échec explicite.

**Aucun secret dans le dépôt.** `${VARIABLE}` sans valeur de repli.

**Javadoc en français** sur toute classe et méthode publique, expliquant l'intention.

**`BigDecimal` partout.** Jamais de `double`.

**Tout fichier SQL contenant du plpgsql** exige `splitStatements:false endDelimiter:;` dans l'en-tête du changeset.

---

## 10. Résumé

```
Étape 1   tests d'invariants base            aucun blocage    ← commencer ici
Étape 2   lecture AS400                      aucun blocage
Étape 3   endpoints de consultation          aucun blocage
Étape 4   transferts                         aucun blocage
Étape 5   scénario complet                   étapes 1 à 4
Étape 6   adaptateur /Trade                  jeton UAT StoneX
```

À l'issue de l'étape 5, la Trading API est fonctionnelle de bout en bout avec un fournisseur simulé. Il ne manquera que le branchement réel sur StoneX, qui ne dépend pas du développement.
