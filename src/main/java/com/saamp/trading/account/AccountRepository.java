package com.saamp.trading.account;

import com.saamp.trading.domain.AccountStatus;
import com.saamp.trading.domain.Asset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<TradingAccount> findByCompanyId(long companyId) {
        return jdbc.query("SELECT * FROM trading_account WHERE company_id = ?", this::map, companyId).stream().findFirst();
    }

    public Optional<TradingAccount> findById(long id) {
        return jdbc.query("SELECT * FROM trading_account WHERE id = ?", this::map, id).stream().findFirst();
    }

    private TradingAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new TradingAccount(
                rs.getLong("id"), rs.getLong("company_id"), Asset.valueOf(rs.getString("base_currency")),
                AccountStatus.valueOf(rs.getString("status")), rs.getBigDecimal("deal_limit"),
                rs.getBigDecimal("position_limit"), rs.getBigDecimal("loss_limit"), rs.getString("as400_ste"),
                (Integer) rs.getObject("as400_nucli_commercial"), (Integer) rs.getObject("as400_nucli_trading"), rs.getInt("config_version"),
                rs.getObject("created_at", java.time.OffsetDateTime.class), rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
