package com.saamp.trading.configsync;

import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;

/** Versioned account configuration copied from MyPortal. */
public record AccountConfigCommand(long companyId, Asset baseCurrency, AccountStatus status,
                                   BigDecimal dealLimit, BigDecimal positionLimit, BigDecimal lossLimit,
                                   String as400Ste, Integer as400NucliCommercial, Integer as400NucliTrading,
                                   int configVersion) {
    public AccountConfigCommand {
        if (as400NucliCommercial != null && as400NucliCommercial.equals(as400NucliTrading)) {
            throw new IllegalArgumentException("AS400 commercial and trading NUCLI must be distinct");
        }
    }
}
