package com.saamp.trading.reservation;

import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.common.TradingException;
import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.TradingMode;
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
    private final com.saamp.trading.account.EffectiveBalanceService operational;

    public ReservationService(BalanceRepository balances, ReservationRepository reservations, TradingProperties properties, com.saamp.trading.account.EffectiveBalanceService operational) {
        this.balances = balances; this.reservations = reservations; this.properties = properties; this.operational=operational;
    }

    @Transactional
    public long reserveCash(long accountId, Asset asset, BigDecimal quantity, long orderId) {
        if (operational.enforced()) throw new TradingException(HttpStatus.CONFLICT,"RULE_B_ADMISSION_REQUIRED",
                "La réservation doit passer par l'admission Rule B et son snapshot officiel");
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
        BigDecimal balance = operational.findAll(accountId).stream().filter(b -> b.asset()==asset).map(b -> b.quantity()).findFirst().orElse(BigDecimal.ZERO);
        return balance.subtract(reservations.activeReserved(accountId, asset));
    }

    /** Returns availability from the same durable LIVE/DEMO reservation namespace as the balance. */
    public BigDecimal available(long accountId, Asset asset, TradingMode tradingMode) {
        BigDecimal balance = operational.findAll(accountId, tradingMode).stream().filter(b -> b.asset()==asset)
                .map(b -> b.quantity()).findFirst().orElse(BigDecimal.ZERO);
        return balance.subtract(reservations.activeReserved(accountId, asset, tradingMode));
    }

    /** Disponible issu du snapshot opérationnel, net des engagements persistants.
     * @param accountId compte @param asset actif @param operationalBalance solde
     * @return disponible sans seconde lecture de compte */
    public BigDecimal available(long accountId, Asset asset, BigDecimal operationalBalance) {
        return operationalBalance.subtract(reservations.activeReserved(accountId, asset));
    }
    public BigDecimal available(long accountId, Asset asset, BigDecimal operationalBalance, TradingMode tradingMode) {
        return operationalBalance.subtract(reservations.activeReserved(accountId, asset, tradingMode));
    }
    @Transactional public void consumeForOrder(long orderId) { reservations.consumeForOrder(orderId); }
    @Transactional public void releaseForOrder(long orderId) { reservations.releaseForOrder(orderId); }

    @Scheduled(fixedDelayString = "${trading.reservations.expiry-scan-delay:30s}")
    @Transactional
    public void expireDue() { reservations.expireDue(properties.getReservations().getTtl()); }
}
