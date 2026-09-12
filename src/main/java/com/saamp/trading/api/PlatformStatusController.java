package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.account.PlatformAvailabilityService;
import com.saamp.trading.security.CurrentTraderService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** État technique du seul contexte authentifié, sans identifiant de compte fourni par le client. */
@RestController
public class PlatformStatusController {
    private final CurrentTraderService traders;
    private final AccountService accounts;
    private final PlatformAvailabilityService availability;

    /** @param traders identité réelle @param accounts périmètre société @param availability état technique */
    public PlatformStatusController(CurrentTraderService traders, AccountService accounts,
            PlatformAvailabilityService availability) {
        this.traders = traders; this.accounts = accounts; this.availability = availability;
    }

    /** @param authentication JWT validé @return HTTP 200 non cachable, y compris pendant une fermeture */
    @GetMapping("/api/v1/platform/status")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS')")
    public ResponseEntity<PlatformAvailabilityService.Status> status(Authentication authentication) {
        var trader = traders.current(authentication);
        var account = accounts.requireByCompany(trader.companyId(), trader.tradingMode());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(availability.status(account, trader.tradingMode()));
    }
}
