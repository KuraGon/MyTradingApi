package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.util.*;

/** Economic comparison for two official captures; representation and SQL order are ignored. */
final class OfficialSnapshotEquivalence {
    private OfficialSnapshotEquivalence() { }

    static boolean businessEquivalent(OfficialTradingBalanceReader.Reading first,
            OfficialTradingBalanceReader.Reading second) {
        return balancesEquivalent(first.balances(), second.balances())
                && clientPostedEquivalent(first.clientPosted(), second.clientPosted());
    }


    static boolean balancesEquivalent(Map<Asset, BigDecimal> first, Map<Asset, BigDecimal> second) {
        if (!first.keySet().equals(second.keySet())) return false;
        for (var key : first.keySet()) if (!decimalEquivalent(first.get(key), second.get(key))) return false;
        return true;
    }

    static boolean clientPostedEquivalent(Map<Long, Boolean> first, Map<Long, Boolean> second) {
        return first.keySet().equals(second.keySet()) && first.keySet().stream()
                .allMatch(key -> Objects.equals(first.get(key), second.get(key)));
    }

    static boolean adjustmentsEquivalent(List<PendingTradingAdjustmentRepository.Adjustment> first,
            List<PendingTradingAdjustmentRepository.Adjustment> second) {
        if (first.size() != second.size()) return false;
        var left = new ArrayList<>(first); var right = new ArrayList<>(second);
        Comparator<PendingTradingAdjustmentRepository.Adjustment> order = Comparator
                .comparingLong(PendingTradingAdjustmentRepository.Adjustment::orderId)
                .thenComparing(a -> a.metal().name(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(a -> a.currency().name(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(a -> a.side().name(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(a -> a.eventId(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(a -> a.movementId(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(a -> a.ste(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(a -> a.nucli(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(a -> a.sicoui(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(a -> a.siprov(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(a -> a.exid(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(a -> a.quantityOz(), OfficialSnapshotEquivalence::compareDecimals)
                .thenComparing(a -> a.grossAmount(), OfficialSnapshotEquivalence::compareDecimals);
        left.sort(order); right.sort(order);
        for (int i = 0; i < left.size(); i++) if (!adjustmentEquivalent(left.get(i), right.get(i))) return false;
        return true;
    }

    private static boolean adjustmentEquivalent(PendingTradingAdjustmentRepository.Adjustment a,
            PendingTradingAdjustmentRepository.Adjustment b) {
        return a.orderId() == b.orderId() && a.metal() == b.metal() && a.currency() == b.currency()
                && a.side() == b.side() && decimalEquivalent(a.quantityOz(), b.quantityOz())
                && decimalEquivalent(a.grossAmount(), b.grossAmount())
                && Objects.equals(a.eventId(), b.eventId()) && Objects.equals(a.movementId(), b.movementId())
                && Objects.equals(a.ste(), b.ste()) && Objects.equals(a.nucli(), b.nucli())
                && Objects.equals(a.sicoui(), b.sicoui()) && Objects.equals(a.siprov(), b.siprov())
                && Objects.equals(a.exid(), b.exid());
    }

    private static boolean decimalEquivalent(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
    private static int compareDecimals(BigDecimal a, BigDecimal b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }
}
