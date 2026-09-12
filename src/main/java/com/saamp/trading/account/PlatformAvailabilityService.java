package com.saamp.trading.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.saamp.trading.domain.TradingMode;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Disponibilité contextuelle, indépendante de la projection LIVE et sans attente réseau. */
@Service
public class PlatformAvailabilityService {
    private final EffectiveBalanceService balances;

    /** @param balances moteur faisant autorité sur la disponibilité officielle */
    public PlatformAvailabilityService(EffectiveBalanceService balances) { this.balances = balances; }

    /** Contrat public fermé : aucune cause technique ni donnée financière. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Status(String status, Instant checkedAt, Integer retryAfterSeconds) { }

    /** @param account compte résolu depuis la société du JWT @param mode mode authentifié
     * @return état public ; une observation LIVE inconnue ou périmée ferme la plateforme */
    public Status status(TradingAccount account, TradingMode mode) {
        AccountService.requireMode(account, mode);
        boolean open = mode == TradingMode.DEMO || balances.officialAvailable(account);
        return new Status(open ? "OPEN" : "TECHNICAL_CLOSURE", Instant.now(), open ? null : 30);
    }
}
