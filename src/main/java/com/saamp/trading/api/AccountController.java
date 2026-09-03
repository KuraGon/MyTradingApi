package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.account.BalanceRepository;
import com.saamp.trading.reservation.ReservationService;
import com.saamp.trading.risk.RiskService;
import com.saamp.trading.security.CurrentTraderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/me")
public class AccountController {
    private final CurrentTraderService traders;
    private final AccountService accounts;
    private final BalanceRepository balances;
    private final ReservationService reservations;
    private final RiskService risk;

    public AccountController(CurrentTraderService traders, AccountService accounts, BalanceRepository balances,
                             ReservationService reservations, RiskService risk) {
        this.traders=traders; this.accounts=accounts; this.balances=balances; this.reservations=reservations; this.risk=risk;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ACCOUNT_READ')")
    public AccountSummaryView summary(Authentication authentication) {
        var trader = traders.current(authentication);
        var account = accounts.requireByCompany(trader.companyId());
        var views = balances.findAll(account.id()).stream()
                .map(b -> new BalanceView(b.asset(),b.quantity(),reservations.available(account.id(),b.asset())))
                .toList();
        return new AccountSummaryView(account.id(),account.companyId(),account.baseCurrency(),account.status(),views,risk.computeAndStore(account));
    }
}
