# Identité du compte officiel

L'autorisation MyTrading LIVE provient du contexte MyPortal CLIENT_SELF et du
contrôle d'appartenance au compte. NREPCO est une donnée AS400 indépendante :
le reader ne l'utilise plus pour autoriser ou refuser une lecture.

Le compte doit conserver son mapping explicite STE/NUCLI trading. La lecture
CLIENOP1 impose ces deux clés et NULIV=0, exige exactement une ligne et vérifie
les trois clés retournées. Une identité absente, dupliquée ou incohérente reste
indisponible. Z/10002 ne satisfait jamais un mapping B/10002.

Les restrictions existantes B/EUR, l'isolation DEMO, les requêtes des soldes,
les conversions grammes/onces, les contrôles de fraîcheur et l'overlay restent
inchangés. Aucune écriture AS400 ni modification de NREPCO n'est nécessaire.
Les autorisations BUY/SELL, Rule B et StoneX ne sont pas modifiées.

Le correctif d'identite ne demande aucune migration. La livraison associee inclut
016 pour les spreads absolus (voir ABSOLUTE_SPREADS.md), sans overmargin ni riskAuthorization.
