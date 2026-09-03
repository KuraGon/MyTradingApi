package com.saamp.trading.pricing;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SpreadConfig(long id, long companyId, Asset asset, BigDecimal spreadBuy, BigDecimal spreadSell,
                           int configVersion, OffsetDateTime activeFrom, OffsetDateTime activeTo) {}
