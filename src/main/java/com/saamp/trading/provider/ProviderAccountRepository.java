package com.saamp.trading.provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Persists the StoneX AccountCode observed from GetCmdtyPositions, independently from TokenID ClientId. */
@Repository
public class ProviderAccountRepository {
    private final JdbcTemplate jdbc;

    public ProviderAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void observe(String provider, String accountCode, String clientId) {
        Optional<String> existing = currentAccountCode(provider);
        if (existing.isPresent() && !existing.get().equals(accountCode)) {
            throw new IllegalStateException("Provider account changed from " + existing.get() + " to " + accountCode);
        }
        jdbc.update("""
                INSERT INTO trading_provider_account(provider,account_code,client_id,first_seen_at,last_seen_at)
                VALUES (?,?,?,NOW(),NOW())
                ON CONFLICT (provider,account_code) DO UPDATE
                  SET client_id=EXCLUDED.client_id,last_seen_at=NOW()
                """, provider, accountCode, clientId);
    }

    public Optional<String> currentAccountCode(String provider) {
        return jdbc.query("""
                SELECT account_code FROM trading_provider_account
                WHERE provider=? ORDER BY last_seen_at DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), provider).stream().findFirst();
    }
}
