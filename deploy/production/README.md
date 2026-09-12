# Préparation production MyTrading

Ces fichiers sont des templates locaux. Ils ne doivent pas être copiés sur un serveur ni exécutés sans une autorisation de déploiement distincte.

## État audité le 2026-09-12

- Cible prévue : `51.44.33.70`, Ubuntu 24.04, noyau `6.17.0-1019-aws`, 2 vCPU, 3.8 Gio RAM, 74 Gio libres sur `/`.
- Aucun Java, PostgreSQL, Nginx, Certbot, utilisateur/groupe `trading`, certificat ou unité `trading-api` n’est présent.
- Seul SSH écoute sur 22 ; UFW est inactif.
- `trading.saamp.com` résout actuellement vers **51.44.33.70** et **52.166.68.213**. Cette ambiguïté est un bloqueur de go-live tant que l’architecture n’est pas confirmée (load balancer volontaire, deuxième instance ou ancien serveur).
- Aucun AAAA ni CNAME n’a été retourné lors de l’audit. Le PTR de l’IP cible est un nom EC2 AWS, utile seulement comme repère.
- La recherche limitée à `/home/ubuntu`, `/var/www`, `/opt` et `/srv` n’a trouvé aucun `index.html`, répertoire `assets`, archive ou build SPA : **FRONT BUILD NOT FOUND**.
- Les sondes publiques directes avec le Host `trading.saamp.com` n’ont identifié aucun service : `51.44.33.70` refuse TCP/80 et expire sur TCP/443 ; `52.166.68.213` expire sur les deux ports. Aucun certificat, en-tête `Server` ni page ne peut donc être attribué à la seconde IP. Son rôle reste inconnu et bloque le go-live.

## Fichiers préparés

- `trading-prod.env.template` : variables sans valeurs secrètes ; à convertir en `/etc/trading-api/trading-prod.env` avec mode `0600 root:root`.
- `trading-api.service` : unité systemd Java 21, journal systemd et permissions minimales.
- `nginx-trading.saamp.com.conf` : vhost TLS, front SPA statique, proxy interne port 8083 et Actuator protégé localement. Son `root` reste volontairement `[À COMPLÉTER]`; les scripts refusent de s’exécuter tant que ce placeholder est présent.
- `preflight.sh`, `install-prerequisites.sh`, `create-postgres.sh`, `backup.sh`, `install-service.sh`, `configure-nginx.sh`, `deploy.sh`, `postflight.sh` et `rollback.sh` : étapes séparées, idempotentes autant que possible et verrouillées par leur variable `APPLY_PRODUCTION_…=YES` propre.
- `prepare-host.sh` : raccourci de préparation idempotente, également verrouillé par `APPLY_PRODUCTION_PREPARE=YES` et refusant le placeholder front.
- `postgresql-bootstrap.sql.template` : rôle et base `trading` dédiés, sans import UAT.

## StoneX / PMXConnect

La référence MySAAMP emploie les clés de configuration `SPOT_ENDPOINT` et `SPOT_TOKEN`. Le mapping MyTrading est :

| MySAAMP | MyTrading |
| --- | --- |
| `SPOT_ENDPOINT` | `PMXCONNECT_BASE_URL` |
| `SPOT_TOKEN` | `PMXCONNECT_TOKEN_ID` |
| version de chemin | `PMXCONNECT_VERSION` |
| environnement porté par le TokenID | `PMXCONNECT_ENVIRONMENT` |

Avant toute ouverture du gate, vérifier en lecture seule `GetSpotRates/SPC/{pair}` et, si une référence historique autorisée existe, `GetRequestStatus`. Aucun `POST /Trade` de test n’est requis ni autorisé par ce runbook.

## AS400 et Effective Balance

La configuration AS400 technique est fournie par les variables `TRADING_AS400_JDBC_URL`, `TRADING_AS400_USERNAME` et `TRADING_AS400_PASSWORD`. Le choix `TRADING_AS400_COMMIT_MODE` doit être validé sur la cible AS400 ; ne pas généraliser `NONE` depuis UAT.

Les mappings métiers sont créés séparément dans `trading_account`, avec le `STE` et le `NUCLI` **trading** propres à chaque compte. Ne jamais copier ni coder en dur `B/20662`, qui est une donnée UAT.

Le mode cible est `ENFORCED`, mais ni ce mode ni un cutover ne doivent être fixés avant validation finale UAT, mesures de fraîcheur AS400 et autorisation métier. Le cutover est une valeur stable, choisie une seule fois pour l’environnement lorsqu’il devient nécessaire.

## Runbook de go-live

1. Confirmer une résolution DNS sans ambiguïté et le certificat TLS pour `trading.saamp.com`.
2. Sauvegarder le serveur et tester une restauration PostgreSQL sur une cible isolée.
3. Installer Java 21, PostgreSQL, Nginx et Certbot suivant les procédures d’exploitation validées.
4. Créer la base `trading` vide et le rôle minimal ; ne jamais recopier `trading_uat`.
5. Poser le WAR exact avec son SHA-256 dans `/opt/trading-api/trading-api.war`, propriétaire `trading:trading`, mode `0644`.
6. Poser l’environnement secret-backed, vérifier `0600 root:root`, puis installer l’unité et le vhost.
7. Conserver `TRADING_EXECUTION_GATE_CLOSE_ON_STARTUP=true` comme configuration explicite. En profil Spring `prod`, le garde de démarrage ferme de toute façon le gate persistant après Liquibase et avant l’exposition du service, même si cette variable est absente ou erronée. Vérifier ensuite que `trading_execution_gate.open=false`.
8. Démarrer le service : Liquibase, health local et logs doivent être sains ; ne pas ouvrir le gate.
9. Vérifier PostgreSQL, les migrations, les sauvegardes et les comptes/mappings métiers explicitement provisionnés.
10. Vérifier AS400 en lecture seule et Effective Balance avec la politique approuvée.
11. Vérifier StoneX en lecture seule (`GetSpotRates`, puis éventuellement `GetRequestStatus` historique autorisé).
12. Vérifier MyPortal LIVE, CORS de production, Risk Monitor et le worker AS400 selon leurs autorisations séparées.
13. Obtenir l’autorisation métier, puis ouvrir l’ExecutionGate. Le premier ordre réel est une opération métier autorisée, jamais un ordre de test.

## Rollback

Avant tout ordre réel, arrêter le service, restaurer le WAR et le fichier d’environnement précédents, puis redémarrer après contrôle de health. Conserver les journaux et le SHA des artefacts.

Un rollback applicatif ne retire pas automatiquement une migration Liquibase ni les données créées après déploiement. Toute restauration PostgreSQL requiert une décision explicite, un backup vérifié et une fenêtre d’exploitation. Ne jamais recréer manuellement des contraintes ou déclencher un rollback de données pour masquer un écart.
