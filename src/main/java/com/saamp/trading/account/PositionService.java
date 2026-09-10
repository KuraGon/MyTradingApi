package com.saamp.trading.account;

import com.saamp.trading.pricing.PricingService;
import com.saamp.trading.risk.MarginRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Prépare les positions consultables sans exposer les prix bruts utilisés en interne. */
@Service
public class PositionService {
    private final BalanceRepository balances;
    private final PricingService pricing;
    private final MarginRateRepository marginRates;

    /**
     * Réunit la liquidation client et la marge configurée pour partager la même base avec la synthèse.
     *
     * @param balances accès aux soldes du compte de trading
     * @param pricing tarification client avec contrôle de fraîcheur
     * @param marginRates taux applicables au compte et au métal
     */
    public PositionService(BalanceRepository balances, PricingService pricing, MarginRateRepository marginRates) {
        this.balances = balances;
        this.pricing = pricing;
        this.marginRates = marginRates;
    }

    /**
     * Retourne les seules positions métal non nulles du compte.
     *
     * @param account compte de trading déjà contrôlé par société
     * @return positions valorisées, sans données internes de spread
     */
    public List<AccountPosition> read(TradingAccount account) {
        return value(account, balances.findAll(account.id()));
    }

    /**
     * Valorise un instantané de soldes sans le relire pendant le calcul de synthèse.
     *
     * <p>Une seule cotation client par métal sert à sa liquidation et à sa marge.
     * La valeur publiée est réutilisée avant le calcul de marge, puis les montants
     * publiés sont additionnés par la synthèse pour conserver une égalité au centime.</p>
     *
     * @param account compte de trading déjà contrôlé par société
     * @param balanceSnapshot soldes de ce compte lus ensemble
     * @return positions et marges publiées à deux décimales HALF_UP
     * @throws com.saamp.trading.common.TradingException si un prix ou un taux requis est indisponible
     */
    public List<AccountPosition> value(TradingAccount account, List<Balance> balanceSnapshot) {
        return value(account, balanceSnapshot,
                asset -> pricing.quoteForDisplay(account.companyId(), asset, account.baseCurrency()),
                asset -> marginRates.currentRate(account.id(), asset));
    }

    /**
     * Réutilise exactement la valorisation existante avec des paramètres acquis hors verrou.
     * @param account compte valorisé
     * @param balanceSnapshot soldes observés
     * @param quotes cotations déjà acquises
     * @param rates taux déjà lus depuis MarginRateRepository
     * @return lignes monétaires publiées
     */
    public List<AccountPosition> value(TradingAccount account, List<Balance> balanceSnapshot,
            java.util.function.Function<com.saamp.trading.domain.Asset, com.saamp.trading.pricing.ClientQuote> quotes,
            java.util.function.Function<com.saamp.trading.domain.Asset, BigDecimal> rates) {
        return balanceSnapshot.stream()
                .filter(balance -> balance.asset().isMetal() && balance.quantity().signum() != 0)
                .map(balance -> {
                    var quote = quotes.apply(balance.asset());
                    var clientPrice = balance.quantity().signum() > 0
                            ? quote.clientSellPrice()
                            : quote.clientBuyPrice();
                    var valuation = balance.quantity().multiply(clientPrice).setScale(2, RoundingMode.HALF_UP);
                    var marginRate = rates.apply(balance.asset());
                    var marginRequirement = valuation.abs().multiply(marginRate).setScale(2, RoundingMode.HALF_UP);
                    var marginRatePct = marginRate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
                    return new AccountPosition(balance.asset(), balance.quantity(), clientPrice, valuation,
                            quote.priceAsOf().toInstant(), marginRatePct, marginRequirement);
                })
                .toList();
    }
}
