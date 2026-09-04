# Alimentation AS400

## Principes

PostgreSQL et `trading_ledger_entry` restent la source de vérité temps réel. Après un ordre définitivement `FILLED`, les besoins de synchronisation AS400 sont enregistrés dans une outbox PostgreSQL atomique avec le settlement. Leur traitement est asynchrone, idempotent et rejouable. Une panne AS400 ne modifie jamais le statut `FILLED` et un rejeu AS400 ne retransmet jamais StoneX.

Une société utilise un STE commun, conservé tel quel et long d'un caractère, mais deux NUCLI distincts : le NUCLI commercial pour les consultations informatives et le NUCLI trading pour la synchronisation et la réconciliation du compte trading.

## Compte poids

Le fichier physique est `GESCOMF.CLCPDP03`. La lecture sélectionne `STE`, le NUCLI demandé, `NULIV = 0` et les circuits `O`, `A`, `P`, `D`. `CIRCUI` est un `CHAR(3)` et doit être nettoyé avec `trim()`.

Les correspondances sont `O → XAU`, `A → XAG`, `P → XPT` et `D → XPD`. `SOLD03` est exprimé en grammes et est converti avec `oz = grammes / 31.1034768`. Les valeurs négatives sont valides à la lecture.

L'écriture directe est autorisée uniquement dans `GESCOMF.CLCPDP03`, pour le NUCLI trading. Elle reflète le solde PostgreSQL courant et ne réalise aucun `INSERT` si la ligne poids est absente. La valeur et la sémantique de `TDMV03` restent inconnues : aucun `UPDATE` réel ne doit être activé avant leur validation.

## Compte devise

La lecture seule utilise la requête validée suivante :

```sql
SELECT SUM(
    CASE
        WHEN l.L1SNS = 'D' THEN l.L1MTT
        ELSE -l.L1MTT
    END
) AS SOLDE
FROM FMPRO.PCGMLFCM l
JOIN GESCOMF.PARSOCP1 s
  ON s.STECPT = l.L1SOC
WHERE s.STE   = ?
  AND l.L1NCA = ?
  AND l.L1NCG IN (401600, 411600)
  AND l.L1ETA = 1
  AND l.L1TYP = 1
```

Le premier paramètre est le STE conservé tel quel et le second le NUCLI demandé. Un résultat `NULL` vaut zéro. Le contrat validé utilise `L1MTT`; l'utilisation éventuelle de `L1MTD` ou `L1DEV` pour des écritures en devise étrangère reste à valider sur les données de test Patrick/comptabilité.

Aucun `INSERT` ni `UPDATE` direct n'est autorisé dans `FMPRO.PCGMLFCM`. Les écritures comptables passent par un programme d'intégration dont le contrat d'appel, le format et le type de pièce BUY/SELL restent à définir.
