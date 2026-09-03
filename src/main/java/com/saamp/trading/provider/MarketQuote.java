package com.saamp.trading.provider;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
public record MarketQuote(String pair, BigDecimal bid, BigDecimal ask, BigDecimal mid, OffsetDateTime asOf) {}
