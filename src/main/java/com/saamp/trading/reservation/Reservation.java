package com.saamp.trading.reservation;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.ReservationStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Reservation(long id, long accountId, Asset asset, BigDecimal quantity, Long orderId, Long batchId, ReservationKind kind,
                          ReservationStatus status, OffsetDateTime expiresAt, OffsetDateTime createdAt) {}
