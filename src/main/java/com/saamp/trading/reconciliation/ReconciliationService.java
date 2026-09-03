package com.saamp.trading.reconciliation;

import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.order.ExecutionGateRepository;
import com.saamp.trading.provider.ProviderAccountRepository;
import com.saamp.trading.provider.ProviderPosition;
import com.saamp.trading.provider.TradingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exact position reconciliation for a dedicated client StoneX account/sub-account.
 * Must stay disabled while client flow is mixed with SAAMP's industrial hedge book.
 */
@Service
@ConditionalOnProperty(name="trading.reconciliation.enabled", havingValue="true")
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String PROVIDER = "PMXCONNECT";

    private final BalanceRepository balances;
    private final TradingProvider provider;
    private final ReconciliationRepository repository;
    private final ExecutionGateRepository gate;
    private final ProviderAccountRepository providerAccounts;

    public ReconciliationService(BalanceRepository balances, TradingProvider provider,
                                 ReconciliationRepository repository, ExecutionGateRepository gate,
                                 ProviderAccountRepository providerAccounts) {
        this.balances = balances;
        this.provider = provider;
        this.repository = repository;
        this.gate = gate;
        this.providerAccounts = providerAccounts;
    }

    @Scheduled(fixedDelayString="${trading.reconciliation.interval:15m}")
    @Transactional
    public void reconcile() {
        long runId = repository.start();
        try {
            List<ProviderPosition> providerPositions = provider.fetchPositions();
            Set<String> accountCodes = providerPositions.stream()
                    .map(ProviderPosition::accountCode)
                    .filter(code -> code != null && !code.isBlank())
                    .collect(Collectors.toSet());
            if (accountCodes.size() > 1) {
                gate.close("RECONCILIATION_MULTIPLE_PROVIDER_ACCOUNTS");
                throw new IllegalStateException("Multiple provider AccountCode values returned: " + accountCodes);
            }
            String accountCode = accountCodes.stream().findFirst()
                    .or(() -> providerAccounts.currentAccountCode(PROVIDER))
                    .orElse(null);
            if (accountCode != null) repository.setProviderAccountCode(runId, accountCode);

            var internal = balances.aggregateAllAccounts();
            var external = new EnumMap<Asset, BigDecimal>(Asset.class);
            providerPositions.stream()
                    .filter(p -> accountCode == null || accountCode.equals(p.accountCode()))
                    .forEach(p -> external.merge(p.asset(), p.quantity(), BigDecimal::add));

            boolean mismatch = false;
            for (Asset asset : Asset.values()) {
                BigDecimal a = internal.getOrDefault(asset, BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);
                BigDecimal b = external.getOrDefault(asset, BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);
                if (a.compareTo(b) != 0) {
                    mismatch = true;
                    repository.difference(runId, asset, a, b);
                }
            }
            if (mismatch) {
                gate.close("RECONCILIATION_MISMATCH");
                repository.finish(runId, "MISMATCH", "Nouvelles transmissions bloquées; revue back-office requise");
                log.error("Trading reconciliation mismatch: execution gate closed, run={}, providerAccount={}", runId, accountCode);
            } else {
                // Deliberately do not re-open a previously closed gate automatically.
                repository.finish(runId, "MATCH", "Positions conformes");
            }
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "reconciliation error" : e.getMessage();
            repository.finish(runId, "ERROR", message.substring(0, Math.min(250, message.length())));
            throw e;
        }
    }
}
