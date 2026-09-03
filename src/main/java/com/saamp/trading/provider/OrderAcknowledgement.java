package com.saamp.trading.provider;
import java.math.BigDecimal;
public record OrderAcknowledgement(AcknowledgementState state, String clientOrderId, String executionId,
                                   BigDecimal rate, String errorCode, String errorMessage) {}
