package com.saamp.trading.as400;

import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.saamp.trading.as400.As400SyncFixtures.*;
import static org.assertj.core.api.Assertions.*;

class As400MovementGroupTest {
    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void missingLegsNeverPromoteEvenIfAllExistingAreSettled(int count) {
        var incomplete=new As400MovementGroup(4,group(As400SyncState.SETTLED).legs().subList(0,count));
        assertThatThrownBy(incomplete::state).hasMessage("AS400_GROUP_INCOMPLETE");
    }

    @Test void globalStateIsMinimumOfEveryExpectedLeg() {
        var legs=new ArrayList<>(group(As400SyncState.SETTLED).legs());
        legs.set(2,group(As400SyncState.ACCEPTED).legs().get(2));
        assertThat(new As400MovementGroup(4,legs).state()).isEqualTo(As400SyncState.ACCEPTED);
        legs.set(0,group(As400SyncState.SUBMITTED).legs().getFirst());
        assertThat(new As400MovementGroup(4,legs).state()).isEqualTo(As400SyncState.SUBMITTED);
    }

    @Test void duplicateIndexCannotMasqueradeAsCompleteGroup() {
        var leg=group(As400SyncState.SETTLED).legs().getFirst();
        assertThatThrownBy(()->new As400MovementGroup(4,List.of(leg,leg,leg,leg)).state())
                .hasMessage("AS400_GROUP_INCOMPLETE");
    }

    @Test void provisionalIdentityCannotChangeOnRetry() {
        var leg=group(As400SyncState.ACCEPTED).legs().getFirst();
        assertThatThrownBy(()->leg.progress(leg.siprov()+1,false)).hasMessage("AS400_SIPROV_CHANGED");
    }
}
