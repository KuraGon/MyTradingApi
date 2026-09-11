package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Instantané d'opération : aucune persistance ne peut en faire un compte officiel autonome.
 * En SHADOW, les quatre composantes permettent de comparer legacy/officiel/overlay/effectif.
 * @param accountId compte concerné
 * @param ste société du compte trading
 * @param nucliTrading identité trading exclusivement
 * @param officialBalances soldes officiels lus
 * @param pendingAdjustments deltas CLIENT non imputés depuis le cutover
 * @param calculatedEffectiveBalances officiel plus deltas ; vide si non calculé ou indisponible
 * @param decisionBalances projection PostgreSQL en LEGACY/SHADOW, calcul effectif en ENFORCED
 * @param startedAt début de l'acquisition
 * @param completedAt fin de l'acquisition
 * @param available fiabilité de l'acquisition pour le mode demandé
 * @param error motif d'indisponibilité éventuel
 * @param clientPosted imputations CLIENT constatées, sans déduction pour les ordres hors cutover
 * @param facts faits persistés permettant la revalidation locale
 * @param enforced obligation d'utiliser le calcul effectif pour la décision */
public record EffectiveBalanceSnapshot(long accountId, String ste, Integer nucliTrading,
        Map<Asset,BigDecimal> officialBalances, Map<Asset,BigDecimal> pendingAdjustments,
        List<Balance> calculatedEffectiveBalances, List<Balance> decisionBalances, Instant startedAt, Instant completedAt,
        boolean available, String error, Map<Long,Boolean> clientPosted,
        List<PendingTradingAdjustmentRepository.Adjustment> facts, boolean enforced) {
    public EffectiveBalanceSnapshot {
        officialBalances=Map.copyOf(officialBalances); pendingAdjustments=Map.copyOf(pendingAdjustments);
        calculatedEffectiveBalances=List.copyOf(calculatedEffectiveBalances); decisionBalances=List.copyOf(decisionBalances);
        clientPosted=Map.copyOf(clientPosted); facts=List.copyOf(facts);
    }
}
