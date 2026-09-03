package com.saamp.trading.risk;

import com.saamp.trading.domain.RiskStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RiskSnapshot(long id, long accountId, OffsetDateTime computedAt, OffsetDateTime priceAsOf,
                           BigDecimal totalFunds, BigDecimal positionValuation, BigDecimal netEquity,
                           BigDecimal marginRequirement, BigDecimal freeEquity, BigDecimal grossPosition,
                           BigDecimal coveragePct, RiskStatus riskStatus) {}
