package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Local-only projection reserved for persisted DEMO orders. It never reads AS400. */
@Repository
public class DemoBalanceRepository {
    private final JdbcTemplate jdbc;

    public DemoBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the complete zero-filled demo projection so a new demo account is deterministic. */
    public List<Balance> findAll(long accountId) {
        return jdbc.query("""
                SELECT ? account_id, v.asset, COALESCE(b.quantity,0) quantity, COALESCE(b.updated_at,NOW()) updated_at
                FROM (VALUES ('EUR'),('USD'),('XAU'),('XAG'),('XPT'),('XPD')) AS v(asset)
                LEFT JOIN trading_demo_balance b ON b.account_id=? AND b.asset=v.asset
                ORDER BY v.asset
                """, this::map, accountId, accountId);
    }

    /** Locks the common account first, then the DEMO-only projection row. */
    public BigDecimal lockQuantity(long accountId, Asset asset) {
        jdbc.queryForObject("SELECT id FROM trading_account WHERE id=? FOR UPDATE", Long.class, accountId);
        jdbc.update("INSERT INTO trading_demo_balance(account_id,asset,quantity) VALUES (?,?,0) ON CONFLICT (account_id,asset) DO NOTHING",
                accountId, asset.name());
        return jdbc.queryForObject("SELECT quantity FROM trading_demo_balance WHERE account_id=? AND asset=? FOR UPDATE",
                BigDecimal.class, accountId, asset.name());
    }

    public BigDecimal updateQuantity(long accountId, Asset asset, BigDecimal quantity) {
        jdbc.update("UPDATE trading_demo_balance SET quantity=?,updated_at=NOW() WHERE account_id=? AND asset=?",
                quantity, accountId, asset.name());
        return quantity;
    }

    private Balance map(ResultSet rs, int rowNum) throws SQLException {
        return new Balance(rs.getLong("account_id"), Asset.valueOf(rs.getString("asset")), rs.getBigDecimal("quantity"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
