package com.saamp.trading.reservation;

import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.Asset;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Short-lived persistent reservations; no DB transaction is held while the user validates an order. */
@Service
public class ReservationService {
    private final BalanceRepository balances;
    private final ReservationRepository reservations;
    private final TradingProperties properties;

    public ReservationService(BalanceRepository balances, ReservationRepository reservations, TradingProperties properties) {
        this.balances = balances; this.reservations = reservations; this.properties = properties;
    }

    @Transactional
    public long reserveCash(long accountId, Asset asset, BigDecimal quantity, long orderId) {
        if (!asset.isCurrency()) throw new IllegalArgumentException("Cash reservation requires currency");
        BigDecimal balance = balances.lockQuantity(accountId, asset);
        BigDecimal alreadyReserved = reservations.activeReserved(accountId, asset);
        BigDecimal available = balance.subtract(alreadyReserved);
        if (available.compareTo(quantity) < 0) {
            throw new TradingException(HttpStatus.CONFLICT, "INSUFFICIENT_AVAILABLE_BALANCE",
                    "Solde disponible insuffisant pour réserver " + asset);
        }
        return reservations.upsert(accountId, asset, quantity, orderId,
                ReservationKind.CASH,
                OffsetDateTime.now().plus(properties.getReservations().getTtl()));
    }

    public BigDecimal available(long accountId, Asset asset) {
        BigDecimal balance = balances.find(accountId, asset).map(b -> b.quantity()).orElse(BigDecimal.ZERO);
        return balance.subtract(reservations.activeReserved(accountId, asset));
    }

    @Transactional public void consumeForOrder(long orderId) { reservations.consumeForOrder(orderId); }
    @Transactional public void releaseForOrder(long orderId) { reservations.releaseForOrder(orderId); }

    @Scheduled(fixedDelayString = "${trading.reservations.expiry-scan-delay:30s}")
    @Transactional
    public void expireDue() { reservations.expireDue(properties.getReservations().getTtl()); }
}
