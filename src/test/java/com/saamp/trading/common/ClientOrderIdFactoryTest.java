package com.saamp.trading.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClientOrderIdFactoryTest {
    @Test void isDeterministicAndFitsPmxLimit() {
        String a = ClientOrderIdFactory.fromIdempotencyKey("4f62ab22-uat-0001");
        String b = ClientOrderIdFactory.fromIdempotencyKey("4f62ab22-uat-0001");
        assertThat(a).isEqualTo(b).startsWith("SAAMP-").hasSizeLessThanOrEqualTo(40);
    }
}
