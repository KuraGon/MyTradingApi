package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.util.*;

/** Lecture officielle exclusivement sur STE et NUCLI trading, jamais sur le commercial. */
public interface OfficialTradingBalanceReader {
    /** Soldes et preuve d'imputation CLIENT observés dans le même passage de lecture. */
    record Reading(Map<Asset,BigDecimal> balances, Map<Long,Boolean> clientPosted) {
        public Reading { balances=Map.copyOf(balances); clientPosted=Map.copyOf(clientPosted); }
    }
    /** @param account identité trading @param adjustments faits à corréler
     * @return soldes et état CLIENT @throws RuntimeException si la lecture n'est pas exploitable */
    Reading read(TradingAccount account, List<PendingTradingAdjustmentRepository.Adjustment> adjustments);
}
