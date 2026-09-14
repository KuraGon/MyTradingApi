# Spreads par metal : ratios historiques et montants absolus

La table existante est `trading_spread`. La migration 016 ajoute `spread_type`
(`PERCENTAGE` ou `ABSOLUTE`) et `price_unit=OZ`. Elle elargit les valeurs BUY/SELL
a `NUMERIC(20,6)` sans changer leur valeur. Toutes les lignes historiques restent
`PERCENTAGE`, avec les limites strictes existantes `0 < valeur < 1`.

Pour ABSOLUTE, la valeur est positive ou nulle : BUY = ask + valeur ; SELL = bid -
valeur. La meme configuration par societe/metal s'applique numeriquement a EUR
et USD. Il n'existe ni devise de configuration ni conversion FX en V1. La devise
vient de la paire de marche, qui doit correspondre au metal et a la devise du
compte. Une future granularite par devise exigera une evolution explicite du
contrat/versionnement ; elle n'est pas activee ici.

`PriceMath.applySpread` est l'unique primitive de calcul. Les prix publies gardent
les arrondis historiques : BUY CEILING, SELL FLOOR, a la precision configuree.
Un montant negatif, une unite autre que OZ, ou un resultat brut/publie non positif
est refuse. Le transport de configuration refuse une precision non representable
dans NUMERIC(20,6), sans arrondir silencieusement les montants.

## Snapshot et reprises

Des le preview, `trading_order` porte `spread_applied`, `spread_type`,
`spread_config_version`, `spread_price_unit`, `spread_quote_scale` et la devise
`spread_quote_currency` derivee de `pair`. Un trigger interdit de modifier ce
snapshot. Prix indicatif marche et client restent dans leurs champs existants.

Le submit revalide la capacite Rule B et le drift avec une cotation fraiche et
le spread persistant de l'ordre. Les autres prix de valorisation du portefeuille
restent les cotations courantes. Un changement de configuration ne remplace pas
le spread de l'ordre. Le prix marche reel d'execution peut evoluer selon le
contrat de transmission existant ; les parametres de spread sont figes.

FILLED utilise exclusivement le snapshot relu sous ACCOUNT puis ORDER. Le
parametre ClientQuote historique de handleFilled reste compatible mais ne fait
plus autorite. Aucun spread ni precision courants ne sont relus pour le settlement.
Les champs market_price/client_price_raw/client_price/gross_amount conservent le
resultat final. PENDING/PENDING_UNKNOWN, y compris apres restart, utilisent ce meme
chemin depuis GetRequestStatus, sans recotation et sans nouveau /Trade.

Les ordres historiques sont marques PERCENTAGE ; leur spread_applied reste
inchange et leur precision de publication historique est six decimales. Un ancien
ordre incomplet sans valeur/version exploitable reste fail-closed au settlement.

## LFMP 10002

Configuration autorisee pour company_id=3, symetrique BUY/SELL :
XAU 0.40, XAG 0.10, XPT 1.00, XPD 1.00, type ABSOLUTE et unite OZ.
Aucune insertion automatique n'est incluse dans la migration. Le provisioning
operationnel suit la validation UAT, avec sauvegarde et ExecutionGate ferme.

## Migration et retour arriere

001..015 sont inchangees. 016 est exclusivement `016-absolute-spread-support.sql`.
Avant toute integration du chantier overmargin local non publie, renumeroter sa
migration Trading en **017** et adapter son master, ses tests et sa documentation.
Ne jamais fusionner deux migrations 016 ni inclure overmargin dans ce WAR.

Le rollback Liquibase de 016 refuse toute presence de configuration ou d'ordre
ABSOLUTE. Apres utilisation, privilegier une correction en avant ; ne jamais
supprimer des ordres/historiques pour rendre un rollback possible. Un retour au
WAR precedent apres utilisation des montants absolus doit rester arrete jusqu'a
revue, car ce WAR interpreterait ces montants comme des ratios.

Aucune modification Rule B, EffectiveBalance, AS400 ecriture, routage DEMO ou
contrat StoneX n'est apportee. Le correctif reader associe retire seulement NREPCO
de l'identification : mapping explicite STE/NUCLI et NULIV=0 restent obligatoires.
