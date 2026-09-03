package com.saamp.trading.risk;

import com.saamp.trading.common.TradingException;
import com.saamp.trading.domain.Asset;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public class MarginRateRepository {
    private final JdbcTemplate jdbc;
    public MarginRateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public BigDecimal currentRate(long accountId, Asset asset) {
        var values = jdbc.query("""
            SELECT rate FROM trading_margin_rate
            WHERE asset=? AND (account_id=? OR account_id IS NULL)
              AND active_from<=NOW() AND (active_to IS NULL OR active_to>NOW())
            ORDER BY CASE WHEN account_id=? THEN 0 ELSE 1 END, active_from DESC
            LIMIT 1
            """, (rs,n) -> rs.getBigDecimal("rate"), asset.name(), accountId, accountId);
        if (values.isEmpty()) throw new TradingException(HttpStatus.CONFLICT, "MARGIN_RATE_MISSING", "Taux de marge absent pour " + asset);
        return values.getFirst();
    }
}
