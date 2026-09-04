package com.saamp.trading.api;

import java.util.List;

/** Page d'ordres client parcourue par identifiant décroissant afin d'éviter les doublons d'OFFSET. */
public record OrderPageView(List<OrderView> items, Long nextCursor, boolean hasMore) {}
