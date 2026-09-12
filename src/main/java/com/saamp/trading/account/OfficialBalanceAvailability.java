package com.saamp.trading.account;

import java.time.*;
import java.util.LinkedHashMap;

/** Observations techniques uniquement : aucun solde n'est conservé ni rendu opposable. */
final class OfficialBalanceAvailability {
    private record Key(long id, String ste, Integer nucli, com.saamp.trading.domain.Asset currency, int version) {
        static Key of(TradingAccount account) {
            return new Key(account.id(), account.as400Ste(), account.as400NucliTrading(),
                    account.baseCurrency(), account.configVersion());
        }
    }
    private record Observation(Instant startedAt, Instant completedAt, boolean available) { }
    private final LinkedHashMap<Key, Observation> observations = new LinkedHashMap<>();

    synchronized void record(TradingAccount account, Instant startedAt, Instant completedAt, boolean available) {
        var key = Key.of(account);
        var previous = observations.get(key);
        // Une ancienne collecte concurrente ne peut effacer un diagnostic plus récent.
        if (previous != null && (completedAt.isBefore(previous.completedAt())
                || available && !previous.available() && !startedAt.isAfter(previous.completedAt()))) return;
        observations.remove(key);
        observations.put(key, new Observation(startedAt, completedAt, available));
        if (observations.size() > 256) observations.remove(observations.keySet().iterator().next());
    }

    synchronized boolean refreshDue(TradingAccount account, Instant now) {
        var observation = observations.get(Key.of(account));
        return observation == null || !now.isBefore(observation.completedAt().plusSeconds(15));
    }

    synchronized Boolean recent(TradingAccount account, Instant now) {
        var observation = observations.get(Key.of(account));
        Duration age = Duration.ofSeconds(45);
        if (observation == null || now.isBefore(observation.completedAt())
                || !now.isBefore(observation.completedAt().plus(age))) return null;
        return observation.available();
    }
}
