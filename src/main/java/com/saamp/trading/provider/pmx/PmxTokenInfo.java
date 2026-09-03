package com.saamp.trading.provider.pmx;

import java.time.Instant;

/** Non-secret metadata decoded from the PMXConnect TokenID payload. */
public record PmxTokenInfo(String environment, String clientId, Instant expiresAt) {}
