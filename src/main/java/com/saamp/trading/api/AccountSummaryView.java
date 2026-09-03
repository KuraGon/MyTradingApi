package com.saamp.trading.api;
import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.risk.RiskResult;
import java.util.List;
public record AccountSummaryView(long accountId, long companyId, Asset baseCurrency, AccountStatus status,
                                 List<BalanceView> balances, RiskResult risk) {}
