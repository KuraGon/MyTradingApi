package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.domain.Asset;
import com.saamp.trading.pricing.ClientQuote;
import com.saamp.trading.pricing.PricingService;
import com.saamp.trading.security.CurrentTraderService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts/me/prices")
public class PricingController {
    private final CurrentTraderService traders; private final AccountService accounts; private final PricingService pricing;
    public PricingController(CurrentTraderService traders, AccountService accounts, PricingService pricing) {
        this.traders=traders; this.accounts=accounts; this.pricing=pricing;
    }

    @GetMapping
    @Operation(summary = "Lire les prix client", description = "Permissions : MYTRADING_ACCESS + MYTRADING_ACCOUNT_READ. Les prix fournisseur bruts ne sont jamais exposés.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ACCOUNT_READ')")
    public List<ClientPriceView> prices(Authentication authentication, @RequestParam(required=false) List<Asset> assets) {
        var trader=traders.current(authentication); var account=accounts.requireByCompany(trader.companyId(), trader.tradingMode());
        var requested=(assets==null||assets.isEmpty())?Asset.metals().stream().toList():assets;
        return requested.stream().filter(Asset::isMetal).map(a -> {
            ClientQuote q = pricing.quoteForDisplay(trader.companyId(),a,account.baseCurrency());
            return new ClientPriceView(a, q.pair(), q.clientBuyPrice(), q.clientSellPrice(), q.priceAsOf());
        }).toList();
    }
}
