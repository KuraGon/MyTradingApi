package com.saamp.trading.pricing;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;

public record AssetConfig(Asset asset, BigDecimal minQuantityOz, int quoteScale,
                          BigDecimal driftTolerance, boolean enabled) {}
