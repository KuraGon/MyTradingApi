package com.saamp.trading.account;

import com.saamp.trading.common.TradingException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final AccountRepository accounts;
    public AccountService(AccountRepository accounts) { this.accounts = accounts; }

    public TradingAccount requireByCompany(long companyId) {
        return accounts.findByCompanyId(companyId)
                .orElseThrow(() -> new TradingException(HttpStatus.NOT_FOUND,"TRADING_ACCOUNT_NOT_FOUND","Compte Trading absent pour cette société"));
    }

    /** @param companyId societe unique du jeton @param mode contexte autorise @return compte compatible */
    public TradingAccount requireByCompany(long companyId, com.saamp.trading.domain.TradingMode mode) {
        var account = requireByCompany(companyId);
        requireMode(account, mode);
        return account;
    }

    /** @param account compte durable @param mode contexte @throws TradingException si contamination de mode */
    public static void requireMode(TradingAccount account, com.saamp.trading.domain.TradingMode mode) {
        if (account.accountMode() != mode) throw new TradingException(HttpStatus.FORBIDDEN,
                "ACCOUNT_TRADING_MODE_MISMATCH", "Le compte ne correspond pas au mode du contexte.");
    }
}
