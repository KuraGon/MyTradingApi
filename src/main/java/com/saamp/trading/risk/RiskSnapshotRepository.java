package com.saamp.trading.risk;

import com.saamp.trading.domain.RiskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class RiskSnapshotRepository {
    private final JdbcTemplate jdbc;
    public RiskSnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(long accountId, OffsetDateTime priceAsOf, RiskResult result) {
        jdbc.update("""
                INSERT INTO trading_risk_snapshot(account_id,price_as_of,total_funds,position_valuation,net_equity,
                  margin_requirement,free_equity,gross_position,coverage_pct,risk_status)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, accountId, priceAsOf, result.totalFunds(), result.positionValuation(), result.netEquity(),
                result.marginRequirement(), result.freeEquity(), result.grossPosition(), result.coveragePct(), result.status().name());
    }

    public Optional<RiskSnapshot> latest(long accountId) {
        return jdbc.query("SELECT * FROM trading_risk_snapshot WHERE account_id=? ORDER BY computed_at DESC,id DESC LIMIT 1", (rs,n) ->
                new RiskSnapshot(rs.getLong("id"), rs.getLong("account_id"), rs.getObject("computed_at", OffsetDateTime.class),
                        rs.getObject("price_as_of", OffsetDateTime.class), rs.getBigDecimal("total_funds"),
                        rs.getBigDecimal("position_valuation"), rs.getBigDecimal("net_equity"), rs.getBigDecimal("margin_requirement"),
                        rs.getBigDecimal("free_equity"), rs.getBigDecimal("gross_position"), rs.getBigDecimal("coverage_pct"),
                        RiskStatus.valueOf(rs.getString("risk_status"))), accountId).stream().findFirst();
    }
}
