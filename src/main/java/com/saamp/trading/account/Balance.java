package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Balance(long accountId, Asset asset, BigDecimal quantity, OffsetDateTime updatedAt) {}
