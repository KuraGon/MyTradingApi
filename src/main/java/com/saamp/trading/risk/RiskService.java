package com.saamp.trading.risk;

import com.saamp.trading.account.AccountPosition;
import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.account.PositionService;
import com.saamp.trading.account.TradingAccount;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;

/** Calcule le risque du compte sur les mêmes liquidations client que la consultation des positions. */
@Service
public class RiskService {
    /**
     * Partage le calcul des positions acquises sans écrire un snapshot prématuré.
     * @param account compte observé
     * @param balanceSnapshot soldes observés
     * @param quotes cotations acquises avant la transaction de décision
     * @param rates taux issus du repository de marge
     * @return calcul unique conservant sa précision de décision
     */
    public RiskCalculator.Evaluation evaluate(TradingAccount account,
            java.util.List<com.saamp.trading.account.Balance> balanceSnapshot,
            java.util.Map<com.saamp.trading.domain.Asset, com.saamp.trading.pricing.ClientQuote> quotes,
            java.util.Map<com.saamp.trading.domain.Asset, BigDecimal> rates) {
        BigDecimal funds = balanceSnapshot.stream().filter(b -> b.asset() == account.baseCurrency())
                .map(com.saamp.trading.account.Balance::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        return RiskCalculator.evaluateAccountPositions(funds, positions.value(account, balanceSnapshot, quotes::get, rates::get));
    }
    private final BalanceRepository balances;
    private final PositionService positions;
    private final RiskSnapshotRepository snapshots;

    /**
     * Réutilise la valorisation client sans accéder à une seconde base de prix fournisseur.
     * @param balances lecture cohérente des fonds et quantités métal
     * @param positions liquidation et marge client par métal
     * @param snapshots historique local des indicateurs calculés
     */
    public RiskService(BalanceRepository balances, PositionService positions, RiskSnapshotRepository snapshots) {
        this.balances = balances;
        this.positions = positions;
        this.snapshots = snapshots;
    }

    /**
     * Publie une synthèse construite à partir des montants des lignes de positions.
     * @param account compte de trading déjà contrôlé par société
     * @return indicateurs utilisant une seule lecture des soldes et des cotations par métal
     * @throws com.saamp.trading.common.TradingException si la liquidation ou la marge ne peut être calculée
     */
    public RiskResult computeAndStore(TradingAccount account) {
        var balanceSnapshot = balances.findAll(account.id());
        BigDecimal totalFunds = balanceSnapshot.stream()
                .filter(balance -> balance.asset() == account.baseCurrency())
                .map(balance -> balance.quantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
        var valuedPositions = positions.value(account, balanceSnapshot);
        Instant oldestPrice = valuedPositions.stream().map(AccountPosition::priceAsOf)
                .min(Comparator.naturalOrder()).orElseGet(Instant::now);
        RiskResult result = RiskCalculator.calculateAccountPositions(totalFunds, valuedPositions);
        snapshots.insert(account.id(), oldestPrice.atOffset(ZoneOffset.UTC), result);
        return result;
    }
}
