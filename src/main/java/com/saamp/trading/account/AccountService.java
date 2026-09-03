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
}
