package com.saamp.trading.provider;

import com.saamp.trading.domain.TradingMode;
import java.util.Objects;

/** Separe le fournisseur d'execution du fournisseur de cotations partagees. */
public final class TradingExecutionProviderRouter {
    private final TradingProvider live;
    private final SimulatedTradingProvider demo;

    /** @param live fournisseur LIVE configure @param demo simulateur local uniquement */
    public TradingExecutionProviderRouter(TradingProvider live, SimulatedTradingProvider demo) {
        this.live = Objects.requireNonNull(live);
        this.demo = Objects.requireNonNull(demo);
    }

    /** @param mode mode durable de l'ordre @return fournisseur autorise, sans fallback LIVE */
    public TradingProvider forMode(TradingMode mode) {
        return switch (Objects.requireNonNull(mode)) {
            case LIVE -> live;
            case DEMO -> demo;
        };
    }
}
