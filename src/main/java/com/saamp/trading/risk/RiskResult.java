package com.saamp.trading.risk;

import com.saamp.trading.domain.RiskStatus;

import java.math.BigDecimal;

public record RiskResult(
        BigDecimal totalFunds,
        BigDecimal positionValuation,
        BigDecimal netEquity,
        BigDecimal marginRequirement,
        BigDecimal freeEquity,
        BigDecimal grossPosition,
        BigDecimal coveragePct,
        RiskStatus status) {
}
