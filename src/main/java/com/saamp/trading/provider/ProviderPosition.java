package com.saamp.trading.provider;

import com.saamp.trading.domain.Asset;

import java.math.BigDecimal;

/** Position returned by the execution provider, scoped by its own AccountCode. */
public record ProviderPosition(String accountCode, Asset asset, BigDecimal quantity) {
    public ProviderPosition {
        if (accountCode == null || accountCode.isBlank()) throw new IllegalArgumentException("accountCode is required");
        if (asset == null || quantity == null) throw new IllegalArgumentException("asset and quantity are required");
    }
}
