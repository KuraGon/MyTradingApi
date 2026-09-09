package com.saamp.trading.reservation;

/** Distingue les fonds de règlement, les fermetures exclusives et la capacité de risque. */
public enum ReservationKind {
    LEGACY, CASH, POSITION_CLOSE, RISK
}
