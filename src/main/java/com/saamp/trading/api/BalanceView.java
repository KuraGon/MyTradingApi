package com.saamp.trading.api;
import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
public record BalanceView(Asset asset, BigDecimal balance, BigDecimal available) {}
