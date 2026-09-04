package com.saamp.trading.order;

import com.saamp.trading.account.TradingAccount;
import com.saamp.trading.api.OrderPageView;
import com.saamp.trading.api.OrderView;
import com.saamp.trading.common.TradingException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Centralise la consultation client des ordres en conservant l'isolation du compte résolu. */
@Service
public class OrderQueryService {
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;
    private final OrderRepository orders;

    /** @param orders accès aux ordres persistés */
    public OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * Lit un ordre uniquement dans le compte courant pour masquer toute ressource externe à la société.
     *
     * @param account compte résolu depuis l'identité authentifiée
     * @param orderId identifiant demandé
     * @return vue client de l'ordre
     * @throws TradingException si l'ordre est absent ou appartient à un autre compte
     */
    public OrderView detail(TradingAccount account, long orderId) {
        return orders.findByIdAndAccountId(orderId, account.id()).map(OrderView::from)
                .orElseThrow(() -> new TradingException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Ordre introuvable"));
    }

    /**
     * Construit une page keyset stable, sans OFFSET, dans le seul compte courant.
     *
     * @param account compte résolu depuis l'identité authentifiée
     * @param cursor identifiant exclusif de reprise
     * @param requestedLimit taille demandée
     * @return page d'ordres triée par identifiant décroissant
     */
    public OrderPageView page(TradingAccount account, Long cursor, Integer requestedLimit) {
        int pageSize = requestedLimit == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(requestedLimit, 1), MAX_PAGE_SIZE);
        var found = orders.findPage(account.id(), cursor, pageSize + 1);
        boolean hasMore = found.size() > pageSize;
        var pageOrders = hasMore ? found.subList(0, pageSize) : found;
        var items = pageOrders.stream().map(OrderView::from).toList();
        Long nextCursor = hasMore ? pageOrders.getLast().id() : null;
        return new OrderPageView(items, nextCursor, hasMore);
    }
}
