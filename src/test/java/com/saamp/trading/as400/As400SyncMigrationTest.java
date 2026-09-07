package com.saamp.trading.as400;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Vérifie le contrat de migration même lorsque PostgreSQL local est indisponible. */
class As400SyncMigrationTest {
    private String migration() throws IOException {
        try (var stream=getClass().getResourceAsStream("/db/changelog/009-as400-sicouvi.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    @Test
    void checkDomainsSeparateTechnicalStatusFromBusinessProgress() throws IOException {
        var sql=migration();
        assertThat(checkValues(sql,"ck_trading_as400_sync_status","status"))
                .containsExactlyInAnyOrder("PENDING","RETRY","PROCESSING","BLOCKED","SYNCED");
        assertThat(checkValues(sql,"ck_trading_as400_sync_state","sync_state"))
                .containsExactlyInAnyOrder("PENDING","SUBMITTED","ACCEPTED","SETTLED","FAILED");
    }

    @Test
    void legacyUpdateOnlyBlocksUnsyncedTargetsAndSetsReviewReason() throws IOException {
        var matcher=Pattern.compile("UPDATE trading_as400_sync_outbox SET ([^;]+);").matcher(migration());
        assertThat(matcher.find()).isTrue();
        String update=matcher.group(1);
        assertThat(update.substring(0,update.indexOf("WHERE")).trim().replaceAll("\\s+"," "))
                .isEqualTo("status='BLOCKED', last_error='AS400_LEGACY_TARGET_REQUIRES_MANUAL_REVIEW'");
        assertThat(update.substring(update.indexOf("WHERE")).trim())
                .isEqualTo("WHERE target IN ('WEIGHT_ACCOUNT','ACCOUNTING') AND status <> 'SYNCED'");
        assertThat(matcher.find()).isFalse();
    }

    @Test
    void dueIndexOnlyContainsAutomaticallyProcessableSicouviEvents() throws IOException {
        var matcher=Pattern.compile("CREATE INDEX idx_trading_as400_sync_due[^;]+;").matcher(migration());
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).contains("target='SICOUVI'",
                "status IN ('PENDING','RETRY','PROCESSING')",
                "sync_state IN ('PENDING','SUBMITTED','ACCEPTED')")
                .doesNotContain("'BLOCKED'","'SYNCED'","'SETTLED'","'FAILED'");
    }

    private String[] checkValues(String sql,String name,String column) {
        var matcher=Pattern.compile("ADD CONSTRAINT "+name+" CHECK \\("+column+" IN \\(([^)]+)\\)\\)").matcher(sql);
        assertThat(matcher.find()).isTrue();
        return Arrays.stream(matcher.group(1).replace("'","").split(",")).map(String::trim).toArray(String[]::new);
    }
}
