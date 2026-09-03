package com.saamp.trading.configsync;

import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;

/** Versioned account configuration copied from MyPortal. */
public record AccountConfigCommand(long companyId, Asset baseCurrency, AccountStatus status,
                                   BigDecimal dealLimit, BigDecimal positionLimit, BigDecimal lossLimit,
                                   int configVersion) {}
