package com.saamp.trading.api;
import com.saamp.trading.account.Balance;
import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.Instant;

/** Solde client du seul compte de trading courant. */
public record BalanceView(Asset asset, BigDecimal balance, BigDecimal available, Instant updatedAt) {

    /**
     * Construit une vue sans conversion d'unité ni perte de précision.
     *
     * @param balance solde persistant du compte courant
     * @param available montant net des réservations actives
     * @return vue client du solde
     */
    public static BalanceView from(Balance balance, BigDecimal available) {
        return new BalanceView(balance.asset(), balance.quantity(), available, balance.updatedAt().toInstant());
    }
}
