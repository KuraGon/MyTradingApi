package com.saamp.trading.account;

import com.saamp.trading.pricing.PricingService;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.List;

/** Prépare les positions consultables sans exposer les prix bruts utilisés en interne. */
@Service
public class PositionService {
    private final BalanceRepository balances;
    private final PricingService pricing;

    /**
     * Assemble la lecture des soldes et la tarification client sans déplacer cette logique dans le contrôleur.
     *
     * @param balances accès aux soldes du compte de trading
     * @param pricing tarification client avec contrôle de fraîcheur
     */
    public PositionService(BalanceRepository balances, PricingService pricing) {
        this.balances = balances;
        this.pricing = pricing;
    }

    /**
     * Retourne les seules positions métal non nulles du compte.
     *
     * <p>Une position longue est liquidée au prix de vente client et une position courte
     * est rachetée au prix d'achat client. Une erreur de fraîcheur provenant du pricing
     * est volontairement propagée.</p>
     *
     * @param account compte de trading déjà contrôlé par société
     * @return positions valorisées, sans données internes de spread
     */
    public List<AccountPosition> read(TradingAccount account) {
        return balances.findAll(account.id()).stream()
                .filter(balance -> balance.asset().isMetal() && balance.quantity().signum() != 0)
                .map(balance -> {
                    var quote = pricing.quoteForDisplay(account.companyId(), balance.asset(), account.baseCurrency());
                    var clientPrice = balance.quantity().signum() > 0
                            ? quote.clientSellPrice()
                            : quote.clientBuyPrice();
                    var valuation = balance.quantity().multiply(clientPrice).setScale(2, RoundingMode.HALF_UP);
                    return new AccountPosition(balance.asset(), balance.quantity(), clientPrice, valuation,
                            quote.priceAsOf().toInstant());
                })
                .toList();
    }
}
