package com.saamp.trading.order;

import com.saamp.trading.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;
    public OrderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insertDraft(long accountId, long companyId, Asset asset, String pair, OrderSide side,
                            java.math.BigDecimal requestedQty, QuantityUnit requestedUnit, java.math.BigDecimal qtyOz,
                            java.math.BigDecimal indicativeMarket, java.math.BigDecimal indicativeClientRaw,
                            java.math.BigDecimal indicativeClient, java.math.BigDecimal spread, int spreadVersion,
                            String idempotencyKey, String clOrdId) {
        var ids = jdbc.query("""
                INSERT INTO trading_order(account_id,company_id,asset,pair,side,order_type,requested_quantity,requested_unit,
                  quantity_oz,status,indicative_price,indicative_client_price_raw,indicative_client_price,spread_applied,spread_config_version,
                  idempotency_key,cl_ord_id)
                VALUES (?,?,?,?,?,'SPOT',?,?,?,'DRAFT',?,?,?,?,?,?,?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING id
                """, (rs,n) -> rs.getLong(1), accountId,companyId,asset.name(),pair,side.name(),requestedQty,requestedUnit.name(),qtyOz,
                indicativeMarket,indicativeClientRaw,indicativeClient,spread,spreadVersion,idempotencyKey,clOrdId);
        return ids.isEmpty() ? -1L : ids.getFirst();
    }

    public Optional<TradingOrder> findById(long id) { return jdbc.query("SELECT * FROM trading_order WHERE id=?", this::map, id).stream().findFirst(); }
    public Optional<TradingOrder> findByIdempotencyKey(String key) { return jdbc.query("SELECT * FROM trading_order WHERE idempotency_key=?", this::map, key).stream().findFirst(); }
    public List<TradingOrder> findPage(long accountId, Long cursor, int limit) {
        if (cursor == null) {
            return jdbc.query("SELECT * FROM trading_order WHERE account_id=? ORDER BY id DESC LIMIT ?", this::map, accountId, limit);
        }
        return jdbc.query("SELECT * FROM trading_order WHERE account_id=? AND id<? ORDER BY id DESC LIMIT ?", this::map, accountId, cursor, limit);
    }

    public Optional<TradingOrder> findByIdAndAccountId(long id, long accountId) {
        return jdbc.query("SELECT * FROM trading_order WHERE id=? AND account_id=?", this::map, id, accountId).stream().findFirst();
    }

    public void markPending(long id) { jdbc.update("UPDATE trading_order SET status='PENDING', submitted_at=NOW() WHERE id=?", id); }
    public void markPendingUnknown(long id, String code, String message) {
        jdbc.update("UPDATE trading_order SET status='PENDING_UNKNOWN',submitted_at=COALESCE(submitted_at,NOW()),stonex_error_code=?,stonex_error_message=?,unknown_since=COALESCE(unknown_since,NOW()),resolution_attempts=0,next_resolution_at=NOW()+INTERVAL '2 seconds' WHERE id=?", code,message,id);
    }

    public void scheduleNextResolution(long id, int attempts, java.time.OffsetDateTime next, boolean manualReview) {
        jdbc.update("UPDATE trading_order SET resolution_attempts=?,next_resolution_at=?,manual_review_at=CASE WHEN ? THEN COALESCE(manual_review_at,NOW()) ELSE manual_review_at END,stonex_error_code=CASE WHEN ? THEN 'MANUAL_REVIEW_REQUIRED' ELSE stonex_error_code END WHERE id=?",
                attempts, next, manualReview, manualReview, id);
    }
    public void markRejected(long id, String code, String message) { jdbc.update("UPDATE trading_order SET status='REJECTED',stonex_error_code=?,stonex_error_message=? WHERE id=?", code,message,id); }
    public void markExpired(long id) { jdbc.update("UPDATE trading_order SET status='EXPIRED' WHERE id=?", id); }
    public void markFilled(long id, String exid, java.math.BigDecimal marketPrice, java.math.BigDecimal clientPriceRaw,
                           java.math.BigDecimal clientPrice, java.math.BigDecimal spread, int spreadVersion,
                           java.math.BigDecimal revenue, java.math.BigDecimal gross) {
        jdbc.update("""
            UPDATE trading_order SET status='FILLED',stonex_exid=?,market_price=?,client_price_raw=?,client_price=?,spread_applied=?,
              spread_config_version=?,saamp_revenue=?,gross_amount=?,executed_at=NOW(),stonex_error_code=NULL,stonex_error_message=NULL
            WHERE id=?
            """, exid,marketPrice,clientPriceRaw,clientPrice,spread,spreadVersion,revenue,gross,id);
    }

    public List<TradingOrder> findPendingUnknownDue(int limit) {
        return jdbc.query("SELECT * FROM trading_order WHERE (status='PENDING_UNKNOWN' AND next_resolution_at<=NOW()) OR (status='PENDING' AND submitted_at<=NOW()-INTERVAL '10 seconds') ORDER BY COALESCE(next_resolution_at,submitted_at) LIMIT ?", this::map, limit);
    }

    private TradingOrder map(ResultSet rs, int n) throws SQLException {
        return new TradingOrder(rs.getLong("id"),rs.getLong("account_id"),rs.getLong("company_id"),(Long)rs.getObject("batch_id"),
                Asset.valueOf(rs.getString("asset")),rs.getString("pair"),OrderSide.valueOf(rs.getString("side")),OrderType.valueOf(rs.getString("order_type")),
                rs.getBigDecimal("requested_quantity"),QuantityUnit.valueOf(rs.getString("requested_unit")),rs.getBigDecimal("quantity_oz"),
                OrderStatus.valueOf(rs.getString("status")),rs.getBigDecimal("indicative_price"),rs.getBigDecimal("indicative_client_price_raw"),
                rs.getBigDecimal("indicative_client_price"),rs.getBigDecimal("market_price"),rs.getBigDecimal("client_price_raw"),
                rs.getBigDecimal("client_price"),rs.getBigDecimal("spread_applied"),(Integer)rs.getObject("spread_config_version"),
                rs.getBigDecimal("saamp_revenue"),rs.getBigDecimal("gross_amount"),rs.getString("idempotency_key"),rs.getString("cl_ord_id"),rs.getString("stonex_exid"),
                rs.getString("stonex_error_code"),rs.getString("stonex_error_message"),rs.getInt("resolution_attempts"),
                rs.getObject("unknown_since",java.time.OffsetDateTime.class),rs.getObject("next_resolution_at",java.time.OffsetDateTime.class),
                rs.getObject("manual_review_at",java.time.OffsetDateTime.class),rs.getObject("created_at",java.time.OffsetDateTime.class),
                rs.getObject("submitted_at",java.time.OffsetDateTime.class),rs.getObject("executed_at",java.time.OffsetDateTime.class));
    }
}
