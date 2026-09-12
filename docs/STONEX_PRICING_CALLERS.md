# Prix StoneX : callers de production

Tous les callers de `TradingProvider.fetchSpotRates` transmettent des paires explicites :

1. `MarketDataRefreshService.refresh(pair)` : `Set.of(pair)` depuis PricingService
   (display, execution, Risk et Risk Monitor). Les lectures display/monitor reutilisent
   le prix local tant qu'il respecte leur fraicheur ; une execution exige un prix frais.
2. `As400FxSource` : `Set.of("EURUSD")`, selon le flux AS400 LIVE existant.
3. `PmxConnectTradingProvider.fetchSpotRate(pair)` : facade mono-paire, sans caller
   applicatif actuel, delegue au meme chemin SPC.

`MarketDataRefreshService.refreshAll()` et son appel vide ont ete retires : aucun caller
ne les utilisait. `PmxConnectTradingProvider.fetchSpotRates` refuse des paires nulles/vides.
Les paires non vides sont canonisees, dedupliquees et lues via `GetSpotRates/SPC/{pair}`.
Cela couvre les quatre metaux contre EUR/USD selon le compte et EURUSD pour le FX.
Le timestamp est commun au jeu de paires d'un appel ; aucun taux brut n'est expose au client.

`fetchLegacyBulkSpotRates()` conserve MTL/FOR explicitement legacy et deprecated.
Seuls les tests de contrat historique l'appellent ; aucun chemin de production ne l'utilise.
Les simulations restent locales. Aucun appel StoneX reel n'a ete effectue pour valider ce patch.
