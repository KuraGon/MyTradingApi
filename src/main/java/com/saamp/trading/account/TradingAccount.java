package com.saamp.trading.account;

import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TradingAccount(long id, long companyId, Asset baseCurrency, AccountStatus status,
                             BigDecimal dealLimit, BigDecimal positionLimit, BigDecimal lossLimit,
                             int configVersion, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
