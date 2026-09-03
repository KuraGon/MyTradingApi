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
        if (!metal.isMetal() || !baseCurrency.isCurrency()) throw new IllegalArgumentException("metal/base currency expected");
        String pair = TradingPair.metalAgainst(metal, baseCurrency);
        if (execution) {
            refresh.refresh(pair);
        }
        MarketPrice market;
        try {
            market = execution ? marketPrices.requireFreshForExecution(pair) : marketPrices.requireFreshForDisplay(pair);
        } catch (TradingException staleOrMissing) {
            if (execution || !("MARKET_PRICE_STALE".equals(staleOrMissing.getCode()) || "MARKET_PRICE_MISSING".equals(staleOrMissing.getCode()))) throw staleOrMissing;
            refresh.refresh(pair);
            market = marketPrices.requireFreshForDisplay(pair);
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
