# AGENTS.md — Trading API SAAMP

Ce fichier est lu automatiquement à chaque session. Il contient les règles permanentes du projet.

**Avant toute modification, lis ce fichier en entier.** Les contraintes ci-dessous ne sont pas des préférences de style : ce système manipule de l'argent client en temps réel, sur des opérations irréversibles, dont la base de données est la seule trace.

---

## 1. Ce que fait ce projet

La Trading API permet à un client SAAMP d'acheter et de vendre des métaux précieux — or, argent, platine, palladium. Les ordres sont exécutés sur le compte StoneX de SAAMP via PMXConnect. Le client n'a aucune relation avec StoneX.

**Trois propriétés à ne jamais casser :**

Une opération transmise à StoneX est **irréversible**. Il n'existe ni annulation ni cotation ferme.

Le fournisseur n'expose **aucun historique d'exécutions**. Le ledger de cette application est la seule trace des opérations.

Le solde d'un client est **de l'argent réel**. Une écriture perdue ou dupliquée est un incident, pas un bug.

---

## 2. Commandes

```bash
mvn clean test          # obligatoire avant toute livraison
mvn clean package
mvn spring-boot:run     # profil dev, port 8082, contexte /trading-api
```

**Ne livre jamais du code sans avoir lancé `mvn clean test` et constaté qu'il passe.**

Les tests base de données utilisent l'environnement historique autorisé : PostgreSQL `localhost:5432/trading`, utilisateur `trading`, mot de passe exclusivement fourni par `TRADING_DB_PASSWORD`. Aucun Docker/Testcontainers ni nouvelle instance. Les seuls tests DDL de migration créent un schéma temporaire, vérifient explicitement `current_schema()` et ne suppriment que leur propre schéma ; aucune opération destructive dans `public`. Fournisseur et cotations simulés/locaux, AS400 et tâches planifiées désactivés pour les contextes de test. Contrôle toujours le nombre de tests exécutés, les erreurs et les ignorés, pas seulement le statut du build.

---

## 3. Invariants métier

### Règle B — capacité pilotée par la Free Equity

La référence détaillée de ce chantier est `../REPRISE_CODEX_RULE_B.md` ; ses décisions Rule B remplacent l'ancienne règle A.

```
Free Equity disponible = netEquity - marginRequirement - RISK actifs des autres ordres
BUY  : CASH couvre le pire coût dans l'enveloppe de drift existante
SELL : aucun nominal CASH réservé, y compris à découvert
```

`POSITION_CLOSE` attribue exclusivement la fermeture d'une position réelle.
`RISK` réserve la consommation positive de Free Equity ; il n'est jamais soustrait du cash disponible.
Les taux proviennent exclusivement de `MarginRateRepository`. Les prix de liquidation client valorisent Risk et Free Equity.
Les limites historiques restent distinctes : deal au prix client, position aux prix marché bid/ask.

Sous marge, seule une fermeture sans traversée de zéro, améliorant strictement la Free Equity et préservant la devise non négative, est admise.
Le disponible est net des réservations actives. Les engagements PENDING/PENDING_UNKNOWN restent actifs après le TTL ; seuls les DRAFT expirent automatiquement. Les LEGACY restent comptés conservativement. Tout LEGACY ACTIVE lié à PENDING/PENDING_UNKNOWN bloque une nouvelle admission Rule B sur le compte jusqu'à résolution, même après TTL.

### Soldes

```
Devise  : ≥ 0 toujours, garanti par déclencheur PostgreSQL
Métal   : peut être négatif — c'est la vente à découvert
```

Ne relâche jamais la contrainte devise « pour faire passer un test ». Si un test échoue dessus, c'est le test ou le code appelant qui est faux.

### Ledger

Le ledger est en **ajout seul**, garanti par déclencheur. Aucun `UPDATE`, aucun `DELETE`. Une correction est une écriture `ADJUSTMENT` de sens inverse.

Les soldes ne sont **jamais écrits directement** : `trading_balance` est la projection de `trading_ledger_entry`. Un trade génère toujours deux écritures dans la même transaction.

### Unité canonique

**L'once troy**, `NUMERIC(20,6)`. C'est l'unité de PMXConnect.

L'affichage en g / kg / oz est une préférence utilisateur appliquée en couche présentation. Chaque ordre conserve la quantité et l'unité telles que saisies, en plus de la valeur canonique.

### Précision

**`BigDecimal` partout. Jamais de `double`, jamais de `float`.**

Dans une chaîne de calcul financier, chaque agrégat destiné à être affiché ou réutilisé est **arrondi à sa précision de publication avant d'entrer dans le calcul suivant**. C'est ainsi que StoneX procède, et un écart d'un centime sur un relevé client est un incident.

---

## 4. Le fournisseur PMXConnect

### Pièges vérifiés en production

**La casse des champs varie selon l'endpoint.**

```
GetSpotRates/MTL  →  "Pair"  "Ask"  "Bid"
GetSpotRates/FOR  →  "PAIR"  "ASK"  "BID"
```

Le mapper est configuré en `ACCEPT_CASE_INSENSITIVE_PROPERTIES` et `USE_BIG_DECIMAL_FOR_FLOATS`. **Ne le reconfigure pas.**

**Deux appels sont nécessaires.** `MTL` ne contient aucune paire de change, `FOR` aucun métal. Le compte est multi-devises : les deux appels sont obligatoires, avec un horodatage commun.

**Les paires n'ont pas de séparateur** : `XAUEUR`, pas `XAU-EUR`. L'interface PMXecute affiche un tiret, l'API le refuse.

**Le nombre de décimales varie** de 1 à 5 selon la paire. Aucun `scale` ne doit être présumé.

**`MMM` n'est pas activé** sur le compte. Ne le mappe pas.

### Résolution d'un état indéterminé

```
Processed               → FILLED
Failed                  → REJECTED
InProcess               → PENDING_UNKNOWN maintenu, repli
400 avec error_code 631 → REJECTED : l'ordre n'a jamais été transmis
400 autre code          → PENDING_UNKNOWN maintenu, ALERTE
5xx, timeout            → PENDING_UNKNOWN maintenu, repli
```

**Seul le code 631 autorise à conclure qu'un ordre n'a pas été exécuté.** Tout autre code d'erreur maintient l'incertitude. Traiter un `400` générique comme un rejet ferait perdre un trade réellement exécuté.

**Aucune retransmission avant résolution.** Un double envoi produirait deux trades réels.

### `/Trade` n'est pas mappé

L'adaptateur lève volontairement une `UnsupportedOperationException`. Les payloads officiels ne sont pas connus et aucun jeton UAT n'est disponible.

**N'invente pas ces payloads.** Un échec bruyant vaut mieux qu'un mappage deviné qui échouerait en production. Utilise `SimulatedTradingProvider` pour tout le reste.

---

## 5. AS400 — lecture et synchronisation encadrée

La lecture AS400 est autorisée hors chemin critique. L'écriture directe est autorisée **uniquement** sur le compte poids `GESCOMF.CLCPDP03` du NUCLI trading. Toute écriture directe sur `FMPRO.PCGMLFCM` est interdite : la comptabilité passe exclusivement par son programme d'intégration. PostgreSQL et `trading_ledger_entry` restent la source de vérité ; la synchronisation AS400 est asynchrone, idempotente et rejouable.

| Règle | Motif |
|---|---|
| Fichiers physiques uniquement (`TABLE_TYPE = 'P'`) | les logiques filtrent de façon imprévisible via JDBC |
| Jamais de `ROW_NUMBER() OVER` | non fiable sur DB2 for i ; utiliser `GROUP BY` + `MAX`/`MIN` |
| Pas de commentaires `/* */` dans les `PreparedStatement` | rejetés par DB2 for i |
| Pas de caractères accentués dans les alias | ASCII uniquement |
| Pas de sous-requête entre AS400 et PostgreSQL | résoudre côté Java, injecter en `IN (?,?,…)` |
| Ne jamais transformer la casse de `STE` | des valeurs minuscules existent |
| `DIGITS()` plutôt que `CHAR()` ou `LPAD()` sur les `DECIMAL` | comportement de cadrage |

**L'AS400 n'est jamais appelé sur le chemin critique d'un ordre.** Uniquement dans le back-office et la préparation des transferts.

Correspondances vérifiées :

```
STE 'I' ↔ STECPT 'LFO'   SAAMP      ← attention, pas 'A'
STE 'B' ↔ STECPT 'LFM'   LFMP

CIRCUI    O = or, A = argent, P = platine, D = palladium
          C, I, R hors périmètre
SOLD03    en grammes, une ligne par métal avec NULIV = 0
```

---

## 6. Sécurité

Les jetons sont émis par MyPortal et validés **localement** via JWKS, sans appel réseau. Issuer `https://myportal.saamp.com`, audience `MYTRADING`, RS256.

**Le périmètre porté par le jeton est informatif.** Vérifie systématiquement que la ressource demandée appartient à la société de l'appelant. Le jeton ne dispense jamais du contrôle d'appartenance.

Une ressource appartenant à une autre société renvoie **`404`, jamais `403`** — ne révèle pas son existence.

Le blocage d'un client passe par `trading_account.status = 'SUSPENDED'`, vérifié à chaque opération. Jamais par la révocation d'un jeton : un access token vit dix minutes et n'est pas révocable.

**Aucun secret dans le dépôt.** `${VARIABLE}` sans valeur de repli. Aucun jeton, code, `code_verifier` ou secret TOTP en journal, même tronqué.

---

## 7. Base de données

Changelogs Liquibase **séquentiels**, jamais de numéro réutilisé ni de saut.

**Tout fichier `.sql` contenant du plpgsql exige :**

```sql
--changeset saamp:00X-nom splitStatements:false endDelimiter:;
```

Sans cet attribut, Liquibase découpe sur le `;` interne du bloc `$$` et PostgreSQL rejette la fonction avec « Unterminated dollar quote ».

Ne modifie jamais un changeset déjà appliqué : ajoutes-en un nouveau.

---

## 8. Conventions de code

Java 21, Spring Boot 3.5.12, PostgreSQL, Liquibase.

**Javadoc en français** sur toute classe et méthode publique. Elle explique **l'intention**, pas la mécanique — pourquoi ce choix, pas ce que fait la ligne suivante. `@param`, `@return`, `@throws` systématiques.

Records Java pour les DTO. `Instant` pour le temps, jamais `Date` ni `LocalDateTime`.

Erreurs du endpoint `/token` au format RFC 6749 ; ailleurs, `application/problem+json`.

Découpage par domaine métier — `account`, `order`, `pricing`, `risk`, `provider` — pas par couche technique.

---

## 9. Comment travailler

**Lis avant d'écrire.** Le code existant porte des décisions réfléchies. Si quelque chose paraît étrange, cherche le commentaire ou la javadoc qui l'explique avant de le corriger.

**Ne réécris pas ce qui fonctionne.** Les corrections demandées sont ciblées. Un refactoring non demandé fait perdre du temps en revue et efface des choix délibérés.

**Compile et teste avant de rendre.** Trois défauts ont franchi une revue parce que le code livré n'avait jamais été exécuté.

**Écris un test pour chaque invariant que tu touches.** Un invariant sans test régressera.

**Signale plutôt que deviner.** Si une information manque — un payload fournisseur, une règle métier, un comportement AS400 — dis-le et propose un échec explicite. Une hypothèse silencieuse coûte plus cher qu'une question.

**Quand tu changes un comportement, dis-le.** Résume ce que tu as modifié, pourquoi, et ce qui reste à vérifier manuellement.

---

## 10. Points ouverts

À ne pas trancher seul :

- **Encours accordé par la direction** — si activé, la contrainte de non-négativité devient une contrainte à `−encours` et le risque de crédit réapparaît. En attente d'arbitrage.
- **Jeton UAT PMXConnect** — bloque le mappage de `/Trade`.
- **Remplissage partiel** — comportement non confirmé. Le contrôle de la quantité retournée est en place ; ne le retire pas.
- **Réconciliation** — manuelle en V1, faute de sous-compte StoneX dédié à l'activité clients.
- **Validation juridique** — requise avant toute mise en production.
