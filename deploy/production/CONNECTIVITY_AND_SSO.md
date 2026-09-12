# Preparation de raccordement - aucune execution de deploiement

## MyPortal PROD : audit read-only du 12 septembre 2026

Serveur 35.181.206.161, application `MYTRADING` id=5, active, type API.
URL actuelle de l'application : `https://mytrading.saamp.com/`.
Issuer effectif releve (WAR et absence d'override runtime) : `https://myportal.saamp.com`.
JWKS : `https://myportal.saamp.com/.well-known/jwks.json`.
Audience JWT : `MYTRADING`. Identifiant SSO : `mytrading` (alias `saampx`).
Authentification publique Authorization Code + PKCE : pas de client_secret a inventer.

Redirections actives relevees dans `application_redirect_uri` :

| Environnement/profil | URI |
| --- | --- |
| DEV / WEB | http://localhost:4202 ; http://localhost:4202/callback |
| DEV / NATIVE | fr.saamp.mytrading.dev://oauth/callback |
| UAT / WEB | https://saamp.samuel-murgia.fr/callback |
| PROD / WEB | https://mytrading.saamp.com ; https://mytrading.saamp.com/callback |
| PROD / NATIVE | fr.saamp.mytrading://oauth/callback |

`trading.saamp.com` ne figure pas dans les origines web ou allowed-return-hosts releves.
Une future intervention autorisee devra :

- Application MYTRADING.url : `https://trading.saamp.com/`.
- Ajouter l'URI PROD/WEB `https://trading.saamp.com/callback` **apres confirmation du
  callback dans le build front manquant**. Ne pas supprimer les URI existantes a l'aveugle.
- Preparer `MYPORTAL_SSO_MYTRADING_REDIRECT_URI=https://trading.saamp.com/callback`
  sous la meme condition de validation du callback.
- Ajouter `https://trading.saamp.com` a `MYPORTAL_MYTRADING_ALLOWED_WEB_ORIGINS`,
  en conservant les origines existantes necessaires aux autres environnements.
- Ajouter `trading.saamp.com` a `MYPORTAL_PASSWORD_ALLOWED_RETURN_HOSTS`, sans effacer
  les autres applications. Aucun ajout cross-site requis pour les deux domaines saamp.com.
- DEMO PROD est optionnel et explicite : autoriser PROD dans MyPortal et provisionner une company SAAMP DEMO ainsi qu'un compte API account_mode=DEMO. Voir docs/DEMO_ISOLATION.md. Le profil prod ne signifie pas LIVE.
- Cote Trading API : `MYPORTAL_JWT_ISSUER=https://myportal.saamp.com`,
  `MYPORTAL_JWT_AUDIENCE=MYTRADING`,
  `MYPORTAL_JWKS_URI=https://myportal.saamp.com/.well-known/jwks.json`.

## StoneX : source et parametres

Reference locale `saamp-main (4).zip/app/Services/SpotApi.php` : les valeurs actives
sont lues par `Setting::all()->pluck('value', 'name')`, pas depuis le .env commente.
La table de configuration MySAAMP PROD n'est pas accessible dans les sources auditees.

**STONEX PROD CONFIG SOURCE NOT FOUND**

| Parametre | Valeur / correspondance |
| --- | --- |
| SPOT_ENDPOINT actif | [À COMPLÉTER] |
| PMXCONNECT_BASE_URL / PMXCONNECT_VERSION | [À COMPLÉTER] ; separer le prefixe et la version pour ne pas doubler le chemin |
| SPOT_TOKEN / PMXCONNECT_TOKEN_ID | [À COMPLÉTER via canal secret] ; utilisation TokenID confirmee dans le code, presence/validite du token PROD non verifiees |
| PMXCONNECT_ENVIRONMENT | [À COMPLÉTER] d'apres les metadonnees du token autorise |
| PMXCONNECT_REQUEST_TIMEOUT | [À CONFIRMER] ; MyTrading a un defaut 15s, pas une valeur MySAAMP validee |

Le wrapper historique ne definit ni CURLOPT_TIMEOUT ni CURLOPT_CONNECTTIMEOUT.
Remarks historique : concatenation `client_id quantite unite cours sigle`.
MyTrading conserve une reference d'ordre sobre et auditable, sans donnees client.
Status historique : POST `SPOT_ENDPOINT + GetRequestStatus`, JSON `ClOrdId`, header
TokenID ; ne pas retransmettre /Trade pour resoudre une incertitude.

## Tests de connectivite a realiser APRES autorisation, avant go-live

Depuis 51.44.33.70, avec limites de temps et sans journaliser credentials/URLs secretes :

1. PostgreSQL local : `pg_isready -h 127.0.0.1 -p 5432 -d trading`, puis connexion
   authentifiee habituelle `BEGIN READ ONLY; SELECT current_database(), current_timestamp;
   ROLLBACK;`. Aucun import de trading_uat.
2. AS400 TCP : `timeout 5 nc -z -w 3 "$AS400_HOST" 8471` (hote fourni par configuration
   autorisee, pas de valeur metier inventee). Un timeout pendant la fermeture connue
   du week-end impose un nouveau test en plage ouverte ; il ne prouve pas une panne permanente.
3. DB2 : depuis un outil JDBC read-only avec le jt400 embarque, identifiants fournis
   sans affichage, connect/login/socket/query timeouts courts ;
   `SELECT CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1`, puis lecteur officiel par mapping
   STE + NUCLI trading valide du client. Pas de hardcode B/20662 et aucune ecriture.
4. StoneX : DNS/TCP/TLS HTTPS vers l'hote autorise, puis
   `GET GetSpotRates/SPC/{paire autorisee}` avec TokenID injecte en memoire par l'outil
   protege. Ne pas placer le token dans une commande curl, une trace verbose ou un rapport.
   Eventuellement GetRequestStatus d'une reference historique autorisee. Jamais /Trade.
5. MyPortal : `curl --fail --silent --show-error --connect-timeout 3 --max-time 10
   --output /dev/null https://myportal.saamp.com/.well-known/jwks.json`, verifier le code
   HTTP, le certificat et issuer/audience attendus. Pas de token utilisateur requis.
6. DNS : `dig +short A trading.saamp.com` et `dig +short AAAA trading.saamp.com`.
   Les deux A actuellement connus sont 51.44.33.70 et 52.166.68.213 ; le second reste
   non identifie. Clarifier cette architecture avant tout changement DNS/TLS.
7. TLS : `timeout 10 openssl s_client -connect trading.saamp.com:443
   -servername trading.saamp.com -verify_return_error </dev/null` ; controler SAN,
   chaine et expiration puis health local. Gate toujours ferme.

**FRONT BUILD NOT FOUND** et seconde IP DNS non identifiee restent des bloqueurs
externes de go-live, sans bloquer les commits backend. `configure-nginx.sh` controle
lui-meme `__FRONT_ROOT_A_COMPLETER__` avant tout backup/installation/reload, independamment
de prepare-host.sh. Aucun des scripts de deploiement n'a ete execute sur le serveur.
