package com.saamp.trading.ledger;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.LedgerEntryType;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Append-only, local ledger for DEMO orders. It shares no projection row with LIVE. */
@Repository
public class DemoLedgerRepository {
    private final JdbcTemplate jdbc;

    public DemoLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long accountId, Asset asset, BigDecimal delta, LedgerEntryType type,
                       long orderId, BigDecimal balanceAfter, String createdBy) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO trading_demo_ledger_entry(account_id,asset,delta,entry_type,order_id,balance_after,created_by) VALUES (?,?,?,?,?,?,?)",
                    new String[]{"id"});
            statement.setLong(1, accountId);
            statement.setString(2, asset.name());
            statement.setBigDecimal(3, delta);
            statement.setString(4, type.name());
            statement.setLong(5, orderId);
            statement.setBigDecimal(6, balanceAfter);
            statement.setString(7, createdBy);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public List<LedgerEntry> findPage(long accountId, Long beforeId, int limit) {
        String sql = beforeId == null
                ? "SELECT * FROM trading_demo_ledger_entry WHERE account_id=? ORDER BY id DESC LIMIT ?"
                : "SELECT * FROM trading_demo_ledger_entry WHERE account_id=? AND id<? ORDER BY id DESC LIMIT ?";
        Object[] args = beforeId == null ? new Object[]{accountId, limit} : new Object[]{accountId, beforeId, limit};
        return jdbc.query(sql, this::map, args);
    }

    private LedgerEntry map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LedgerEntry(rs.getLong("id"), rs.getLong("account_id"), Asset.valueOf(rs.getString("asset")),
                rs.getBigDecimal("delta"), LedgerEntryType.valueOf(rs.getString("entry_type")),
                rs.getObject("order_id", Long.class), null, rs.getBigDecimal("balance_after"),
                rs.getObject("created_at", java.time.OffsetDateTime.class), rs.getString("created_by"));
    }
}
