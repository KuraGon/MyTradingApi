package com.saamp.trading.provider;
import java.math.BigDecimal;
public record ExecutionReport(ExecutionState state, String clientOrderId, String executionId,
                              BigDecimal fillRate, String errorCode, String errorMessage) {}
