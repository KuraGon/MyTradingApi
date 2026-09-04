package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.order.*;
import com.saamp.trading.security.CurrentTraderService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts/me/orders")
public class OrderController {
    private final CurrentTraderService traders; private final AccountService accounts; private final OrderExecutionService execution; private final OrderQueryService queries;
    public OrderController(CurrentTraderService traders, AccountService accounts, OrderExecutionService execution, OrderQueryService queries) {
        this.traders=traders; this.accounts=accounts; this.execution=execution; this.queries=queries;
    }

    @PostMapping("/preview")
    @Operation(summary = "Prévisualiser un ordre SPOT", description = "Permissions : MYTRADING_ACCESS + MYTRADING_ORDER_WRITE. Crée un brouillon et ses réservations avant validation explicite par l'utilisateur.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ORDER_WRITE')")
    public OrderPreviewResponse preview(Authentication authentication, @Valid @RequestBody OrderPreviewRequest request) {
        var trader=traders.current(authentication); return execution.preview(trader.companyId(),trader.userId(),request);
    }

    @PostMapping("/{orderId}/submit")
    @Operation(summary = "Soumettre un ordre prévisualisé", description = "Permissions : MYTRADING_ACCESS + MYTRADING_ORDER_WRITE. Ne jamais retransmettre automatiquement un ordre PENDING_UNKNOWN.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_ORDER_WRITE')")
    public OrderView submit(Authentication authentication,@PathVariable long orderId) {
        var trader=traders.current(authentication); return OrderView.from(execution.submit(orderId,trader.companyId(),trader.userId()));
    }

    @GetMapping
    @Operation(summary = "Lire l'historique paginé des ordres", description = "Permissions : MYTRADING_ACCESS + MYTRADING_HISTORY_READ. Pagination keyset par id décroissant, curseur exclusif, sans OFFSET.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_HISTORY_READ')")
    public OrderPageView history(Authentication authentication,
                                 @RequestParam(required = false) Long cursor,
                                 @RequestParam(required = false) Integer limit) {
        var trader=traders.current(authentication); var account=accounts.requireByCompany(trader.companyId());
        return queries.page(account, cursor, limit);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Suivre un ordre", description = "Permissions : MYTRADING_ACCESS + MYTRADING_HISTORY_READ. Route canonique de polling d'un ordre PENDING ou PENDING_UNKNOWN ; ne transmet aucun ordre.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_HISTORY_READ')")
    public OrderView detail(Authentication authentication, @PathVariable long orderId) {
        var trader=traders.current(authentication); var account=accounts.requireByCompany(trader.companyId());
        return queries.detail(account, orderId);
    }
}
