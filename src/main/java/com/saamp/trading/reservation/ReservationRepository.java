package com.saamp.trading.reservation;

import com.saamp.trading.domain.Asset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class ReservationRepository {
    private final JdbcTemplate jdbc;
    public ReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public BigDecimal activeReserved(long accountId, Asset asset) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM trading_reservation WHERE account_id=? AND asset=? AND status='ACTIVE' AND expires_at>NOW()",
                BigDecimal.class, accountId, asset.name());
        return value == null ? BigDecimal.ZERO : value;
    }

    public long insert(long accountId, Asset asset, BigDecimal quantity, Long orderId, Long batchId, OffsetDateTime expiresAt) {
        var key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO trading_reservation(account_id,asset,quantity,order_id,batch_id,status,expires_at) VALUES (?,?,?,?,?,'ACTIVE',?)",
                    new String[]{"id"});
            ps.setLong(1, accountId); ps.setString(2, asset.name()); ps.setBigDecimal(3, quantity);
            if (orderId == null) ps.setNull(4, java.sql.Types.BIGINT); else ps.setLong(4, orderId);
            if (batchId == null) ps.setNull(5, java.sql.Types.BIGINT); else ps.setLong(5, batchId);
            ps.setObject(6, expiresAt); return ps;
        }, key);
        return key.getKey().longValue();
    }

    public boolean hasActiveForOrder(long orderId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM trading_reservation WHERE order_id=? AND status='ACTIVE' AND expires_at>NOW()", Integer.class, orderId);
        return count != null && count > 0;
    }

    public void consumeForOrder(long orderId) {
        jdbc.update("UPDATE trading_reservation SET status='CONSUMED' WHERE order_id=? AND status='ACTIVE'", orderId);
    }

    public void releaseForOrder(long orderId) {
        jdbc.update("UPDATE trading_reservation SET status='RELEASED' WHERE order_id=? AND status='ACTIVE'", orderId);
    }

    public int expireDue() {
        return jdbc.update("UPDATE trading_reservation SET status='EXPIRED' WHERE status='ACTIVE' AND expires_at<=NOW()");
    }
}
