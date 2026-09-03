package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.risk.RiskResult;
import com.saamp.trading.risk.RiskService;
import com.saamp.trading.security.CurrentTraderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/me/risk")
public class RiskController {
    private final CurrentTraderService traders; private final AccountService accounts; private final RiskService risk;
    public RiskController(CurrentTraderService traders, AccountService accounts, RiskService risk) { this.traders=traders;this.accounts=accounts;this.risk=risk; }
    @GetMapping
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ACCOUNT_READ')")
    public RiskResult current(Authentication authentication) {
        var trader=traders.current(authentication); return risk.computeAndStore(accounts.requireByCompany(trader.companyId()));
    }
}
