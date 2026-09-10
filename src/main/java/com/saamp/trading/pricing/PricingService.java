package com.saamp.trading.pricing;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.common.TradingPair;
import com.saamp.trading.domain.Asset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/** Applies the company-specific BUY/SELL spread to raw market prices. */
@Service
public class PricingService {
    private final PricingRepository repository;
    private final MarketPriceService marketPrices;
    private final MarketDataRefreshService refresh;

    public PricingService(PricingRepository repository, MarketPriceService marketPrices, MarketDataRefreshService refresh) {
        this.repository = repository; this.marketPrices = marketPrices; this.refresh = refresh;
    }

    public ClientQuote quoteForDisplay(long companyId, Asset metal, Asset baseCurrency) {
        return build(companyId, metal, baseCurrency, false);
    }

    public ClientQuote quoteForExecution(long companyId, Asset metal, Asset baseCurrency) {
        return build(companyId, metal, baseCurrency, true);
    }

    private ClientQuote build(long companyId, Asset metal, Asset baseCurrency, boolean execution) {
        return build(companyId, metal, baseCurrency, execution, null);
    }

    /**
     * Acquiert les mêmes prix client avec la fraîcheur propre au monitor.
     * @param companyId société
     * @param metal métal
     * @param baseCurrency devise
     * @param maxAge fraîcheur autorisée
     * @return cotation client
     * @throws TradingException si prix ou paramétrage indisponibles
     */
    public ClientQuote quoteForMonitor(long companyId, Asset metal, Asset baseCurrency, java.time.Duration maxAge) {
        return build(companyId, metal, baseCurrency, false, java.util.Objects.requireNonNull(maxAge));
    }

    private ClientQuote build(long companyId, Asset metal, Asset baseCurrency, boolean execution, java.time.Duration maxAge) {
        if (!metal.isMetal() || !baseCurrency.isCurrency()) throw new IllegalArgumentException("metal/base currency expected");
        String pair = TradingPair.metalAgainst(metal, baseCurrency);
        if (execution) {
            refresh.refresh(pair);
        }
        MarketPrice market;
        try {
            market = execution ? marketPrices.requireFreshForExecution(pair) :
                    maxAge == null ? marketPrices.requireFreshForDisplay(pair) : marketPrices.requireFresh(pair, maxAge);
        } catch (TradingException staleOrMissing) {
            if (execution || !("MARKET_PRICE_STALE".equals(staleOrMissing.getCode()) || "MARKET_PRICE_MISSING".equals(staleOrMissing.getCode()))) throw staleOrMissing;
            refresh.refresh(pair);
            market = maxAge == null ? marketPrices.requireFreshForDisplay(pair) : marketPrices.requireFresh(pair, maxAge);
        }
        SpreadConfig spread = repository.findCurrentSpread(companyId, metal, OffsetDateTime.now())
                .orElseThrow(() -> new TradingException(HttpStatus.CONFLICT, "SPREAD_NOT_CONFIGURED", "Spread non configuré pour " + metal));
        AssetConfig assetConfig = repository.findAssetConfig(metal)
                .orElseThrow(() -> new TradingException(HttpStatus.CONFLICT, "ASSET_NOT_CONFIGURED", "Actif non configuré: " + metal));
        if (!assetConfig.enabled()) throw new TradingException(HttpStatus.CONFLICT, "ASSET_DISABLED", "Actif désactivé: " + metal);
        return new ClientQuote(metal, pair, market.bid(), market.ask(),
                PriceMath.rawClientPrice(market.ask(), spread.spreadBuy(), com.saamp.trading.domain.OrderSide.BUY),
                PriceMath.rawClientPrice(market.bid(), spread.spreadSell(), com.saamp.trading.domain.OrderSide.SELL),
                PriceMath.clientPrice(market.ask(), spread.spreadBuy(), com.saamp.trading.domain.OrderSide.BUY, assetConfig.quoteScale()),
                PriceMath.clientPrice(market.bid(), spread.spreadSell(), com.saamp.trading.domain.OrderSide.SELL, assetConfig.quoteScale()),
                spread.spreadBuy(), spread.spreadSell(), spread.configVersion(), market.priceAsOf());
    }
}
