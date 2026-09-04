package com.saamp.trading.account;

import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TradingAccount(long id, long companyId, Asset baseCurrency, AccountStatus status,
                             BigDecimal dealLimit, BigDecimal positionLimit, BigDecimal lossLimit,
                             String as400Ste, Integer as400NucliCommercial, Integer as400NucliTrading,
                             int configVersion, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public TradingAccount(long id, long companyId, Asset baseCurrency, AccountStatus status,
                          BigDecimal dealLimit, BigDecimal positionLimit, BigDecimal lossLimit,
                          int configVersion, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, companyId, baseCurrency, status, dealLimit, positionLimit, lossLimit,
                null, null, null, configVersion, createdAt, updatedAt);
    }
}
