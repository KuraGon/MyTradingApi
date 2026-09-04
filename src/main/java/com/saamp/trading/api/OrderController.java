package com.saamp.trading.api;

import com.saamp.trading.account.AccountService;
import com.saamp.trading.order.*;
import com.saamp.trading.security.CurrentTraderService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts/me/orders")
public class OrderController {
    private final CurrentTraderService traders; private final AccountService accounts; private final OrderExecutionService execution; private final OrderRepository orders;
    public OrderController(CurrentTraderService traders, AccountService accounts, OrderExecutionService execution, OrderRepository orders) {
        this.traders=traders; this.accounts=accounts; this.execution=execution; this.orders=orders;
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
    @Operation(summary = "Lire l'historique des ordres", description = "Permissions : MYTRADING_ACCESS + MYTRADING_HISTORY_READ.")
    @PreAuthorize("hasAuthority('MYTRADING_ACCESS') and hasAuthority('MYTRADING_HISTORY_READ')")
    public List<OrderView> history(Authentication authentication,@RequestParam(defaultValue="100") int limit) {
        var trader=traders.current(authentication); var account=accounts.requireByCompany(trader.companyId());
        return orders.findRecent(account.id(),Math.min(Math.max(limit,1),500)).stream().map(OrderView::from).toList();
    }
}
