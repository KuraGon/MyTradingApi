package com.saamp.trading.as400;
import com.saamp.trading.domain.Asset; import java.math.BigDecimal;
/** Diagnostic back-office sans correction automatique. */
public record As400ReconciliationResult(Asset asset,BigDecimal balancePostgres,BigDecimal balanceAs400,BigDecimal difference,boolean synchronizedBalance) {}
