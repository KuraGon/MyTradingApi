package com.saamp.trading.api;
import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;

/** Synthèse client composée à partir du calcul de risque métier existant. */
public record AccountSummaryView(long accountId, Asset baseCurrency, AccountStatus status,
                                 BigDecimal dealLimit, BigDecimal positionLimit, RiskSummaryView risk) {}
