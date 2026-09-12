# Disponibilité de la plateforme — contrat front

## Contexte authentifié

`GET /trading-api/api/v1/platform/status` exige un JWT MyPortal valide et
`MYTRADING_ACCESS`. Le serveur résout le compte depuis `companyId` et vérifie
l'égalité entre `tradingMode` du token et `account_mode` du compte. Aucun identifiant
de compte n'est accepté en paramètre.

- `INTERNAL_DEMO / DEMO` sur compte DEMO : OPEN, y compris en PROD. Aucun appel
  officiel, overlay ou probe n'est lancé. L'opt-in DEMO et les autres contrôles JWT
  restent obligatoires.
- `CLIENT_SELF / LIVE` sur compte LIVE : OPEN seulement sur preuve officielle
  récente. Un état inconnu, expiré ou non exploitable signifie TECHNICAL_CLOSURE.
- La future délégation LIVE aura la même politique ; `INTERNAL_DELEGATED` reste
  interdit en V1.

HTTP **200**, `Content-Type: application/json`, `Cache-Control: no-store` :

```json
{"status":"OPEN","checkedAt":"2026-09-12T12:00:00Z","retryAfterSeconds":null}
```

```json
{"status":"TECHNICAL_CLOSURE","checkedAt":"2026-09-12T12:00:00Z","retryAfterSeconds":30}
```

Les dates ci-dessus sont des exemples de contrat, jamais des valeurs de configuration.
`checkedAt` est l'instant UTC d'évaluation de la réponse, pas une date de solde.
Les erreurs d'authentification/périmètre restent 401/403/404 selon les règles existantes.
Le HTTP 200 n'est garanti que lorsque l'API et son référentiel de comptes fonctionnent.

## Observation serveur et limites

L'observation est alimentée par la **même double capture EffectiveBalance** que le
moteur : identité, devise, lectures concordantes, overlay concordant et fraîcheur
`max-snapshot-age` doivent être valides. Toute capture officielle échouée met à jour
l'état négatif. Une revalidation métier échouée invalide également l'observation.
Aucun solde n'est conservé dans cet état technique.

L'état est en mémoire, par compte + mapping trading + devise + version de
configuration, limité à 256 entrées. Il n'est ni partagé entre comptes ni persistant.
Sa durée maximale est **45 secondes après la fin de la capture** ; un appel status
demande un renouvellement dès **15 secondes**. Cette marge permet le polling front
à 30 secondes sans expirer systématiquement entre deux essais. Un redémarrage ou
une éviction donne un état inconnu, donc fermé jusqu'à une nouvelle preuve.

Le status ne fait aucune attente réseau officielle. Si nécessaire il soumet une
capture au **même exécuteur unique que SHADOW**, sans file d'attente de captures,
avec son circuit existant : une tâche au maximum et pause de 30 secondes après
échec. Les requêtes simultanées sont coalescées. Une tâche occupée ou un circuit
ouvert n'est jamais une preuve de disponibilité. Le premier appel à froid peut
retourner fermé même si la dépendance fonctionne ; le prochain retry constate la
collecte terminée. Un renouvellement réussi rétablit OPEN sans restart.

Le probe emploie une copie JDBC read-only avec les propriétés jt400 21.0.6 déjà
vérifiées : `login timeout=1` seconde et `socket timeout=1000` millisecondes,
avec query timeout de 5 secondes. Ces bornes sont **par connexion/I/O/requête**,
pas une promesse de durée totale pour le N+1 overlay. Une capture dépassant
`max-snapshot-age` est rejetée ; elle ne peut déclarer OPEN. Même si une collecte
est lente, aucun thread HTTP status n'attend sa fin et aucune autre capture de
statut n'est empilée. Aucun probe n'est planifié sans demande ni lecture moteur.
Les timeouts de la connexion métier ENFORCED et du worker restent inchangés.

Une capture LEGACY ordinaire n'alimente jamais une preuve officielle. Le probe
status peut vérifier la source officielle en LEGACY, sans utiliser la projection
PostgreSQL pour conclure OPEN ; l'overlay LEGACY reste non activé. Le statut ne
modifie pas le contrat métier LEGACY/SHADOW (décisions locales), ni ENFORCED
(décisions officielles obligatoires). Le front qui consomme le nouveau contrat
peut présenter une fermeture LIVE même en SHADOW lorsque l'officiel est indisponible.

**OPEN n'autorise aucun ordre.** Le backend refait les captures/revalidations
ENFORCED nécessaires et les autres contrôles d'admission. La durée de 45 secondes
ne prolonge jamais la validité d'un snapshot financier et ne fournit aucun fallback
PostgreSQL LIVE. Une panne après un OPEN reste traitée par le contrat 503 ci-dessous.
Le gate, les permissions, le risque et les prix peuvent refuser une opération même
si l'état officiel est OPEN. DEMO garde sa projection locale et son exécution simulée.

## Erreur publique des endpoints métier

Une erreur officielle (`OFFICIAL_BALANCE_UNAVAILABLE` ou
`OFFICIAL_CURRENCY_UNSUPPORTED` en interne) est traduite par `ApiExceptionHandler` :

HTTP **503**, `Retry-After: 30`, `Cache-Control: no-store` :

```json
{"code":"PLATFORM_TEMPORARILY_UNAVAILABLE","message":"La plateforme est momentanément indisponible pour des raisons techniques."}
```

Aucune propriété/message interne de l'exception n'est recopié. Les autres contrats
d'erreur ne changent pas. Les diagnostics serveur existants donnent accountId,
mode, disponibilité, durées, nombre de faits et motif générique, sans soldes ni secrets.

## Intégration mobile

Au démarrage après authentification, consulter status avant la navigation métier.
OPEN : affichage normal. TECHNICAL_CLOSURE : écran portrait plein écran conforme
à la maquette de référence remise à l'équipe front. Les sources Flutter ne sont pas
présentes dans ce dépôt : le présent document est le contrat à implémenter.

Titre : **Plateforme temporairement indisponible**

Texte :

> La plateforme est momentanément fermée pour des raisons techniques.
>
> Merci de réessayer dans quelques instants.

Bouton principal : **Réessayer**, qui rappelle status. Loading court et double clic
bloqué ; si fermé, rester sur la page. Si OPEN, revenir à l'accueil (ou à une
consultation précédente sûre). Ne jamais restaurer automatiquement un formulaire
d'ordre non soumis.

Bouton secondaire : **Contacter le support**.
Destination : **[À COMPLÉTER PAR L'ÉQUIPE FRONT / MÉTIER]**.

Conserver l'identité visuelle MyTrading et la session. Aucune navigation métier
active, aucun écran de balance/ordre visible derrière cette page, bottom navigation
visuellement inactive. Aucun logout automatique. Ne jamais afficher AS400, DB2,
JDBC, IP, hostname, exception, timeout interne, nom de table ou de connecteur.

**Intercepteur global obligatoire** : sur tout appel API, HTTP 503 **et**
`code=PLATFORM_TEMPORARILY_UNAVAILABLE` entraîne la navigation vers cette page,
sans toast simultané. Ne pas assimiler un autre 503 à ce code ; ne pas perdre la
session. Une réponse issue d'un ancien contexte doit être ignorée après changement
de compte/token (notamment un ancien LIVE après passage DEMO).

Polling facultatif toutes les **30 secondes**, uniquement page visible et application
au premier plan. Arrêter en arrière-plan, éviter les appels concurrents et les
boucles agressives. Après TECHNICAL_CLOSURE → OPEN, afficher le retour disponible
puis revenir à Accueil. Une erreur réseau reste un état inconnu, jamais un OPEN
inventé. Un 401 suit le flux d'authentification normal et n'est pas une fermeture.

## Vérification locale

Les tests couvrent l'expiration et l'isolation des observations, la reprise sans
restart, le probe bloqué sans attente front ni duplication, la double lecture
obligatoire du moteur, DEMO en profil PROD sans accès officiel, le périmètre JWT,
la deny-by-default allowlist et le corps 503 strict sans détail technique.
Aucune migration, configuration d'environnement, écriture distante ou action de
front n'est effectuée par ce patch.
