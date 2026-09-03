package com.saamp.trading.statement;

import com.saamp.trading.domain.Asset;

import java.time.Instant;
import java.util.List;

/** Jeu de données structuré du relevé, destiné au front et à un futur rendu PDF. */
public record AccountStatement(long accountId, Asset baseCurrency, Instant generatedAt,
                               List<StatementLine> lines, Long nextCursor, boolean hasMore) {}
