package com.saamp.trading.provider.pmx;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class PmxTokenInspectorTest {

    @Test
    void decodesEnvironmentClientIdAndExpiryWithoutUsingTheSignature() {
        long exp = Instant.parse("2030-12-31T23:59:59Z").getEpochSecond();
        String token = jwt("{\"alg\":\"none\"}", "{\"Entity\":\"SFL\",\"ClientId\":\"S0011\",\"env\":\"prod\",\"exp\":" + exp + "}");

        PmxTokenInfo info = PmxTokenInspector.inspect(token, new PmxConnectJson());

        assertThat(info.environment()).isEqualTo("prod");
        assertThat(info.clientId()).isEqualTo("S0011");
        assertThat(info.expiresAt()).isEqualTo(Instant.ofEpochSecond(exp));
    }

    public static String jwt(String header, String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}
