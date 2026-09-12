package com.saamp.trading.domain;

/**
 * Durable execution context carried from the MyPortal JWT to the persisted order.
 *
 * <p>DEMO is deliberately distinct from LIVE so that asynchronous infrastructure
 * can enforce the isolation after the authenticated HTTP request has ended.</p>
 */
public enum TradingMode {
    LIVE,
    DEMO
}
