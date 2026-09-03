package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.ledger.LedgerEntry;
import com.saamp.trading.ledger.LedgerRepository;
import com.saamp.trading.security.CurrentTraderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts/me/ledger")
public class LedgerController {
    private final CurrentTraderService traders; private final AccountService accounts; private final LedgerRepository ledger;
    public LedgerController(CurrentTraderService traders, AccountService accounts, LedgerRepository ledger) {
        this.traders=traders; this.accounts=accounts; this.ledger=ledger;
    }
    @GetMapping
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_HISTORY_READ')")
    public List<LedgerEntry> history(Authentication authentication, @RequestParam(defaultValue="100") int limit) {
        var trader=traders.current(authentication); var account=accounts.requireByCompany(trader.companyId());
        return ledger.findRecent(account.id(),Math.min(Math.max(limit,1),500));
    }
}
