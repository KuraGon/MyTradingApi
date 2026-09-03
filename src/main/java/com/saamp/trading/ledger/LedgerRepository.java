package com.saamp.trading.ledger;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.LedgerEntryType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class LedgerRepository {
    private final JdbcTemplate jdbc;
    public LedgerRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insert(long accountId, Asset asset, BigDecimal delta, LedgerEntryType type,
                       Long orderId, String transferRef, BigDecimal balanceAfter, String createdBy) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO trading_ledger_entry(account_id,asset,delta,entry_type,order_id,transfer_ref,balance_after,created_by) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, accountId); ps.setString(2, asset.name()); ps.setBigDecimal(3, delta); ps.setString(4, type.name());
            if (orderId == null) ps.setNull(5, java.sql.Types.BIGINT); else ps.setLong(5, orderId);
            ps.setString(6, transferRef); ps.setBigDecimal(7, balanceAfter); ps.setString(8, createdBy);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public List<LedgerEntry> findRecent(long accountId, int limit) {
        return jdbc.query("SELECT * FROM trading_ledger_entry WHERE account_id=? ORDER BY created_at DESC,id DESC LIMIT ?", (rs,n) ->
                new LedgerEntry(rs.getLong("id"), rs.getLong("account_id"), Asset.valueOf(rs.getString("asset")),
                        rs.getBigDecimal("delta"), LedgerEntryType.valueOf(rs.getString("entry_type")),
                        (Long) rs.getObject("order_id"), rs.getString("transfer_ref"), rs.getBigDecimal("balance_after"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class), rs.getString("created_by")), accountId, limit);
    }
}
