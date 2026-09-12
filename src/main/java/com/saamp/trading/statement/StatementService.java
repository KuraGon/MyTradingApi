package com.saamp.trading.statement;

import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.ledger.LedgerRepository;
import com.saamp.trading.ledger.DemoLedgerRepository;
import com.saamp.trading.domain.TradingMode;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Construit le relevé client depuis le ledger en ajout seul, unique source historique fiable. */
@Service
public class StatementService {
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;
    private final LedgerRepository ledger;
    private final DemoLedgerRepository demoLedger;

    /**
     * Utilise le ledger plutôt que la projection courante des soldes afin de préserver l'historique.
     *
     * @param ledger accès en lecture au journal append-only
     */
    public StatementService(LedgerRepository ledger) {
        this(ledger, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public StatementService(LedgerRepository ledger, DemoLedgerRepository demoLedger) {
        this.ledger = ledger;
        this.demoLedger = demoLedger;
    }

    /**
     * Produit les lignes du relevé du seul compte préalablement résolu par société.
     *
     * @param account compte courant de l'utilisateur authentifié
     * @param cursor identifiant exclusif de reprise, ou {@code null} pour la première page
     * @param requestedLimit taille de page demandée
     * @return relevé structuré issu du ledger
     */
    public AccountStatement build(TradingAccount account, Long cursor, Integer requestedLimit) {
        return build(account, cursor, requestedLimit, TradingMode.LIVE);
    }
    public AccountStatement build(TradingAccount account, Long cursor, Integer requestedLimit, TradingMode tradingMode) {
        int pageSize = requestedLimit == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(requestedLimit, 1), MAX_PAGE_SIZE);
        var entries = tradingMode == TradingMode.DEMO
                ? demoLedger().findPage(account.id(), cursor, pageSize + 1)
                : ledger.findPage(account.id(), cursor, pageSize + 1);
        boolean hasMore = entries.size() > pageSize;
        var pageEntries = hasMore ? entries.subList(0, pageSize) : entries;
        var lines = pageEntries.stream()
                .map(entry -> new StatementLine(entry.id(), entry.asset(), entry.delta(), entry.entryType(),
                        entry.orderId(), entry.balanceAfter(), entry.createdAt().toInstant()))
                .toList();
        Long nextCursor = hasMore ? pageEntries.getLast().id() : null;
        return new AccountStatement(account.id(), account.baseCurrency(), Instant.now(), lines, nextCursor, hasMore);
    }
    private DemoLedgerRepository demoLedger() {
        if (demoLedger == null) throw new IllegalStateException("DEMO_LEDGER_UNAVAILABLE");
        return demoLedger;
    }
}
