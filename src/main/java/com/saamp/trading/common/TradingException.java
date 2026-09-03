package com.saamp.trading.common;

import org.springframework.http.HttpStatus;

/** Functional API error with a stable machine-readable error code. */
public class TradingException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public TradingException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
