package com.saamp.trading.risk;

import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.common.TradingPair;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.pricing.MarketPrice;
import com.saamp.trading.pricing.MarketPriceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;

/** Computes client-account risk from local balances and fresh market prices. */
@Service
public class RiskService {
    private final BalanceRepository balances;
    private final MarginRateRepository marginRates;
    private final MarketPriceService prices;
    private final RiskSnapshotRepository snapshots;

    public RiskService(BalanceRepository balances, MarginRateRepository marginRates,
                       MarketPriceService prices, RiskSnapshotRepository snapshots) {
        this.balances = balances; this.marginRates = marginRates; this.prices = prices; this.snapshots = snapshots;
    }

    public RiskResult computeAndStore(TradingAccount account) {
        BigDecimal totalFunds = balances.find(account.id(), account.baseCurrency()).map(b -> b.quantity()).orElse(BigDecimal.ZERO);
        var positions = new ArrayList<RiskPosition>();
        OffsetDateTime oldestPrice = OffsetDateTime.now();
        for (Asset metal : Asset.metals()) {
            BigDecimal qty = balances.find(account.id(), metal).map(b -> b.quantity()).orElse(BigDecimal.ZERO);
            if (qty.signum() == 0) continue;
            String pair = TradingPair.metalAgainst(metal, account.baseCurrency());
            MarketPrice market = prices.requireFreshForDisplay(pair);
            // Conservative mark: long positions valued on bid, short positions on ask.
            BigDecimal mark = qty.signum() >= 0 ? market.bid() : market.ask();
            positions.add(new RiskPosition(qty, mark, marginRates.currentRate(account.id(), metal)));
            if (market.priceAsOf().isBefore(oldestPrice)) oldestPrice = market.priceAsOf();
        }
        RiskResult result = RiskCalculator.calculate(totalFunds, positions);
        snapshots.insert(account.id(), oldestPrice, result);
        return result;
    }
}
