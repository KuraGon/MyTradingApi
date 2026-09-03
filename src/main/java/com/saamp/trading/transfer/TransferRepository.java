package com.saamp.trading.transfer;

import com.saamp.trading.domain.Asset;
import com.saamp.trading.domain.TransferDirection;
import com.saamp.trading.domain.TransferStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class TransferRepository {
    private final JdbcTemplate jdbc;
    public TransferRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<TradingTransfer> findByExternalRef(String externalRef) {
        return jdbc.query("SELECT * FROM trading_transfer WHERE external_ref=?", (rs,n) -> new TradingTransfer(
                rs.getLong("id"), rs.getLong("account_id"), Asset.valueOf(rs.getString("asset")), rs.getBigDecimal("quantity"),
                TransferDirection.valueOf(rs.getString("direction")), rs.getString("external_ref"), rs.getBigDecimal("acquisition_price"),
                TransferStatus.valueOf(rs.getString("status")), rs.getObject("received_at", java.time.OffsetDateTime.class),
                rs.getObject("applied_at", java.time.OffsetDateTime.class), rs.getString("rejected_reason")), externalRef).stream().findFirst();
    }

    public long insertReceived(long accountId, Asset asset, java.math.BigDecimal quantity, TransferDirection direction,
                               String externalRef, java.math.BigDecimal acquisitionPrice) {
        var ids = jdbc.query("""
                INSERT INTO trading_transfer(account_id,asset,quantity,direction,external_ref,acquisition_price,status)
                VALUES (?,?,?,?,?,?,'RECEIVED')
                ON CONFLICT (external_ref) DO NOTHING
                RETURNING id
                """, (rs,n) -> rs.getLong(1), accountId,asset.name(),quantity,direction.name(),externalRef,acquisitionPrice);
        return ids.isEmpty() ? -1L : ids.getFirst();
    }

    public void markApplied(long id) {
        jdbc.update("UPDATE trading_transfer SET status='APPLIED', applied_at=NOW(), rejected_reason=NULL WHERE id=?", id);
    }

    public void markRejected(long id, String reason) {
        jdbc.update("UPDATE trading_transfer SET status='REJECTED', rejected_reason=? WHERE id=?", reason, id);
    }
}
