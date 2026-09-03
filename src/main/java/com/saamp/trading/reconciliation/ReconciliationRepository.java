package com.saamp.trading.reconciliation;

import com.saamp.trading.domain.Asset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public class ReconciliationRepository {
    private final JdbcTemplate jdbc;
    public ReconciliationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public long start() {
        return jdbc.queryForObject("INSERT INTO trading_reconciliation_run DEFAULT VALUES RETURNING id", Long.class);
    }
    public void setProviderAccountCode(long runId, String accountCode) {
        jdbc.update("UPDATE trading_reconciliation_run SET provider_account_code=? WHERE id=?", accountCode, runId);
    }

    public void difference(long runId, Asset asset, BigDecimal internal, BigDecimal provider) {
        jdbc.update("INSERT INTO trading_reconciliation_difference(run_id,asset,internal_quantity,provider_quantity,delta) VALUES (?,?,?,?,?)",
                runId,asset.name(),internal,provider,internal.subtract(provider));
    }
    public void finish(long runId,String status,String message) {
        jdbc.update("UPDATE trading_reconciliation_run SET status=?,message=?,completed_at=NOW() WHERE id=?",status,message,runId);
    }
}
