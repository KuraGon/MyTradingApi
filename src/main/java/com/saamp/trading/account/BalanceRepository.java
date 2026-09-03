package com.saamp.trading.account;

import com.saamp.trading.domain.Asset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class BalanceRepository {
    private final JdbcTemplate jdbc;

    public BalanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Balance> findAll(long accountId) {
        return jdbc.query("SELECT account_id, asset, quantity, updated_at FROM trading_balance WHERE account_id = ? ORDER BY asset", this::map, accountId);
    }

    public java.util.Map<Asset, BigDecimal> aggregateAllAccounts() {
        var rows = jdbc.query("SELECT asset, COALESCE(SUM(quantity),0) quantity FROM trading_balance GROUP BY asset",
                (rs,n) -> java.util.Map.entry(Asset.valueOf(rs.getString("asset")), rs.getBigDecimal("quantity")));
        var result = new java.util.EnumMap<Asset, BigDecimal>(Asset.class);
        for (var row : rows) result.put(row.getKey(), row.getValue());
        return result;
    }

    public Optional<Balance> find(long accountId, Asset asset) {
        return jdbc.query("SELECT account_id, asset, quantity, updated_at FROM trading_balance WHERE account_id=? AND asset=?", this::map, accountId, asset.name()).stream().findFirst();
    }

    /** Locks one balance row, creating it at zero first when it does not exist. */
    public BigDecimal lockQuantity(long accountId, Asset asset) {
        jdbc.update("INSERT INTO trading_balance(account_id, asset, quantity) VALUES (?,?,0) ON CONFLICT (account_id,asset) DO NOTHING", accountId, asset.name());
        return jdbc.queryForObject("SELECT quantity FROM trading_balance WHERE account_id=? AND asset=? FOR UPDATE", BigDecimal.class, accountId, asset.name());
    }

    public BigDecimal updateQuantity(long accountId, Asset asset, BigDecimal quantity) {
        jdbc.update("UPDATE trading_balance SET quantity=?, updated_at=NOW() WHERE account_id=? AND asset=?", quantity, accountId, asset.name());
        return quantity;
    }

    private Balance map(ResultSet rs, int rowNum) throws SQLException {
        return new Balance(rs.getLong("account_id"), Asset.valueOf(rs.getString("asset")), rs.getBigDecimal("quantity"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
