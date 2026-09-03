package com.saamp.trading.configsync;

import com.saamp.trading.common.TradingException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Local versioned copy of MyPortal trading configuration.
 * Transport is intentionally outside this service: an event consumer and periodic reconciliation
 * can call the same idempotent methods without introducing a synchronous MyPortal dependency.
 */
@Service
public class TradingConfigurationSyncService {
    private final JdbcTemplate jdbc;
    public TradingConfigurationSyncService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void applyAccount(AccountConfigCommand c) {
        if (!c.baseCurrency().isCurrency()) throw new IllegalArgumentException("Base currency must be EUR or USD");
        Integer current = jdbc.query("SELECT config_version FROM trading_account WHERE company_id=? FOR UPDATE",
                (rs,n) -> rs.getInt(1), c.companyId()).stream().findFirst().orElse(null);
        if (current != null && c.configVersion() < current) return; // out-of-order event
        if (current == null) {
            jdbc.update("""
                    INSERT INTO trading_account(company_id,base_currency,status,deal_limit,position_limit,loss_limit,config_version)
                    VALUES (?,?,?,?,?,?,?)
                    """, c.companyId(),c.baseCurrency().name(),c.status().name(),c.dealLimit(),c.positionLimit(),c.lossLimit(),c.configVersion());
        } else {
            jdbc.update("""
                    UPDATE trading_account SET base_currency=?,status=?,deal_limit=?,position_limit=?,loss_limit=?,
                      config_version=?,updated_at=NOW() WHERE company_id=?
                    """, c.baseCurrency().name(),c.status().name(),c.dealLimit(),c.positionLimit(),c.lossLimit(),c.configVersion(),c.companyId());
        }
    }

    @Transactional
    public void applySpread(SpreadConfigCommand c) {
        if (!c.asset().isMetal()) throw new IllegalArgumentException("Spread asset must be a metal");
        if (c.spreadBuy()==null || c.spreadSell()==null || c.spreadBuy().signum()<=0 || c.spreadSell().signum()<=0)
            throw new TradingException(HttpStatus.BAD_REQUEST,"INVALID_SPREAD","BUY et SELL spread doivent être strictement positifs");
        OffsetDateTime activeFrom = c.activeFrom()==null ? OffsetDateTime.now() : c.activeFrom();
        Integer maxVersion = jdbc.query("SELECT MAX(config_version) FROM trading_spread WHERE company_id=? AND asset=?",
                (rs,n) -> (Integer)rs.getObject(1), c.companyId(),c.asset().name()).stream().findFirst().orElse(null);
        if (maxVersion != null && c.configVersion() <= maxVersion) return;
        jdbc.update("UPDATE trading_spread SET active_to=? WHERE company_id=? AND asset=? AND active_to IS NULL AND active_from<?",
                activeFrom,c.companyId(),c.asset().name(),activeFrom);
        jdbc.update("""
                INSERT INTO trading_spread(company_id,asset,spread_buy,spread_sell,config_version,active_from)
                VALUES (?,?,?,?,?,?)
                """, c.companyId(),c.asset().name(),c.spreadBuy(),c.spreadSell(),c.configVersion(),activeFrom);
    }
}
