package com.saamp.trading.as400;

import java.util.List;

/** Interdit de promouvoir un sous-ensemble incomplet de mouvements. */
record As400MovementGroup(int expectedLegCount, List<As400Movement> legs) {
    As400MovementGroup {
        legs=List.copyOf(legs);
    }

    void validate() {
        if (expectedLegCount <= 0 || legs.size()!=expectedLegCount
                || legs.stream().map(As400Movement::legIndex).distinct().count()!=expectedLegCount
                || legs.stream().anyMatch(l -> l.legIndex()<0 || l.legIndex()>=expectedLegCount)
                || legs.stream().map(l -> l.data().sicoui()).distinct().count()!=expectedLegCount)
            throw new As400SyncDataException("AS400_GROUP_INCOMPLETE");
    }

    As400SyncState state() {
        validate();
        if (legs.stream().anyMatch(l -> l.state()==As400SyncState.FAILED)) return As400SyncState.FAILED;
        return legs.stream().map(As400Movement::state)
                .min(java.util.Comparator.comparingInt(Enum::ordinal)).orElseThrow();
    }
}
