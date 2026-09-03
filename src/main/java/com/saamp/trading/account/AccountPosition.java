package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;

import java.math.BigDecimal;
import java.time.Instant;

/** Position métal valorisée pour consultation avec un prix effectivement proposé au client. */
public record AccountPosition(Asset asset, BigDecimal quantityOz, BigDecimal clientPrice,
                              BigDecimal valuation, Instant priceAsOf) {}
