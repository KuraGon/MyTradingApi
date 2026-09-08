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


    @Test
    void migration010MovesPhysicalIdentityWithoutEditing009() throws IOException {
        String sql=groupMigration();
        assertThat(sql).contains("DROP CONSTRAINT ck_trading_as400_sicouvi_identity",
                "DROP CONSTRAINT ck_trading_as400_progress",
                "CREATE TABLE trading_as400_movement",
                "UNIQUE(event_id,leg_index)","UNIQUE(event_id,sicoui)",
                "expected_leg_count","fx_frozen_at","stonex_exid");
        assertThat(sql).doesNotContain("UNIQUE(sicoui)","DROP COLUMN sicoui","DROP COLUMN siprov");
    }

    @Test
    void migration010BlocksLegacySingleMovementWithoutDestroyingHistory() throws IOException {
        var matcher=Pattern.compile("UPDATE trading_as400_sync_outbox\\s+SET ([^;]+);").matcher(groupMigration());
        assertThat(matcher.find()).isTrue();
        var update=matcher.group(1);
        assertThat(update).contains("status='BLOCKED'","AS400_LEGACY_SINGLE_MOVEMENT_REQUIRES_MANUAL_REVIEW",
                "workflow_version=1","status <> 'SYNCED'");
        assertThat(update).doesNotContain("sync_state=","sicoui=","siprov=","submitted_at=","accepted_at=","settled_at=");
    }

    @Test
    void migration010DueIndexExcludesEveryLegacyWorkflowAndTerminalStatus() throws IOException {
        var matcher=Pattern.compile("CREATE INDEX idx_trading_as400_sync_due[^;]+;").matcher(groupMigration());
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).contains("workflow_version=2","target='SICOUVI'",
                "status IN ('PENDING','RETRY','PROCESSING')","sync_state IN ('PENDING','SUBMITTED','ACCEPTED')")
                .doesNotContain("'BLOCKED'","'SYNCED'","'FAILED'");
    }

    private String groupMigration() throws IOException {
        try (var stream=getClass().getResourceAsStream("/db/changelog/010-as400-movement-group.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private String[] checkValues(String sql,String name,String column) {
        var matcher=Pattern.compile("ADD CONSTRAINT "+name+" CHECK \\("+column+" IN \\(([^)]+)\\)\\)").matcher(sql);
        assertThat(matcher.find()).isTrue();
        return Arrays.stream(matcher.group(1).replace("'","").split(",")).map(String::trim).toArray(String[]::new);
    }
}
