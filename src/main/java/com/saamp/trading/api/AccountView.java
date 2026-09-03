package com.saamp.trading.api;

import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;

import java.time.Instant;

/** Informations fonctionnelles du compte rattaché à la société authentifiée. */
public record AccountView(long id, Asset baseCurrency, AccountStatus status, Instant createdAt, Instant updatedAt) {

    /**
     * Écarte les paramètres internes de risque et de configuration inutiles à l'écran compte.
     *
     * @param account compte métier déjà résolu par société
     * @return vue client minimale du compte
     */
    public static AccountView from(TradingAccount account) {
        return new AccountView(account.id(), account.baseCurrency(), account.status(),
                account.createdAt().toInstant(), account.updatedAt().toInstant());
    }
}
