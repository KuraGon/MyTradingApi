package com.saamp.trading.pricing;

import com.saamp.trading.domain.Asset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class PricingRepository {
    private final JdbcTemplate jdbc;
    public PricingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<MarketPrice> findMarketPrice(String pair) {
        return jdbc.query("SELECT * FROM trading_market_price WHERE pair=?", (rs,n) -> new MarketPrice(
                rs.getString("pair"), rs.getBigDecimal("bid"), rs.getBigDecimal("ask"), rs.getBigDecimal("mid"),
                rs.getObject("price_as_of", OffsetDateTime.class), rs.getString("source"), rs.getObject("updated_at", OffsetDateTime.class)), pair).stream().findFirst();
    }


    public List<MarketPrice> findAllMarketPrices() {
        return jdbc.query("SELECT * FROM trading_market_price ORDER BY pair", (rs,n) -> new MarketPrice(
                rs.getString("pair"), rs.getBigDecimal("bid"), rs.getBigDecimal("ask"), rs.getBigDecimal("mid"),
                rs.getObject("price_as_of", OffsetDateTime.class), rs.getString("source"),
                rs.getObject("updated_at", OffsetDateTime.class)));
    }

    public List<MarketPrice> findMarketPrices(List<String> pairs) {
        if (pairs.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(pairs.size(), "?"));
        return jdbc.query("SELECT * FROM trading_market_price WHERE pair IN (" + marks + ") ORDER BY pair", (rs,n) -> new MarketPrice(
                rs.getString("pair"), rs.getBigDecimal("bid"), rs.getBigDecimal("ask"), rs.getBigDecimal("mid"),
                rs.getObject("price_as_of", OffsetDateTime.class), rs.getString("source"), rs.getObject("updated_at", OffsetDateTime.class)), pairs.toArray());
    }

    public void upsertMarketPrice(MarketPrice p) {
        jdbc.update("""
                INSERT INTO trading_market_price(pair,bid,ask,mid,price_as_of,source,updated_at)
                VALUES (?,?,?,?,?,?,NOW())
                ON CONFLICT (pair) DO UPDATE SET bid=EXCLUDED.bid,ask=EXCLUDED.ask,mid=EXCLUDED.mid,
                  price_as_of=EXCLUDED.price_as_of,source=EXCLUDED.source,updated_at=NOW()
                """, p.pair(), p.bid(), p.ask(), p.mid(), p.priceAsOf(), p.source());
    }

    public Optional<SpreadConfig> findCurrentSpread(long companyId, Asset asset, OffsetDateTime now) {
        return jdbc.query("""
                SELECT * FROM trading_spread
                WHERE company_id=? AND asset=? AND active_from<=? AND (active_to IS NULL OR active_to>?)
                ORDER BY active_from DESC LIMIT 1
                """, (rs,n) -> new SpreadConfig(rs.getLong("id"), rs.getLong("company_id"), Asset.valueOf(rs.getString("asset")),
                rs.getBigDecimal("spread_buy"), rs.getBigDecimal("spread_sell"), rs.getInt("config_version"),
                rs.getObject("active_from", OffsetDateTime.class), rs.getObject("active_to", OffsetDateTime.class)),
                companyId, asset.name(), now, now).stream().findFirst();
    }

    public Optional<AssetConfig> findAssetConfig(Asset asset) {
        return jdbc.query("SELECT * FROM trading_asset_config WHERE asset=?", (rs,n) -> new AssetConfig(
                Asset.valueOf(rs.getString("asset")), rs.getBigDecimal("min_quantity_oz"), rs.getInt("quote_scale"),
                rs.getBigDecimal("drift_tolerance"), rs.getBoolean("enabled")), asset.name()).stream().findFirst();
    }
}
