package com.saamp.trading.statement;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record StatementLine(Asset asset, BigDecimal balance, BigDecimal clientPrice,
                            BigDecimal value, OffsetDateTime priceAsOf) {}
