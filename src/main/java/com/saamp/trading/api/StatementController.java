package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.security.CurrentTraderService;
import com.saamp.trading.statement.AccountStatement;
import com.saamp.trading.statement.StatementService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/me/statement")
public class StatementController {
    private final CurrentTraderService traders; private final AccountService accounts; private final StatementService statements;
    public StatementController(CurrentTraderService traders, AccountService accounts, StatementService statements) {
        this.traders=traders;this.accounts=accounts;this.statements=statements;
    }
    @GetMapping
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_HISTORY_READ')")
    public AccountStatement statement(Authentication authentication) {
        var trader=traders.current(authentication);
        return statements.build(accounts.requireByCompany(trader.companyId()));
    }
}
