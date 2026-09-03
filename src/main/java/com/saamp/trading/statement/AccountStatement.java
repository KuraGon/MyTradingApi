package com.saamp.trading.statement;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Structured source used by future JSON and PDF renderers. */
public record AccountStatement(long accountId, long companyId, Asset baseCurrency, OffsetDateTime generatedAt,
                               List<StatementLine> lines, BigDecimal totalValue) {}
