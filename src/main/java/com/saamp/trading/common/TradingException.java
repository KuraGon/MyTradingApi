package com.saamp.trading.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Functional API error with a stable machine-readable error code. */
public class TradingException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    public TradingException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    /**
     * Transporte des propriétés structurées déjà disponibles sans exposer de détail fournisseur.
     *
     * @param status statut HTTP de l'erreur
     * @param code code fonctionnel stable
     * @param message explication destinée à l'utilisateur
     * @param properties propriétés client-safe à joindre au problem detail
     */
    public TradingException(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getProperties() { return properties; }
}
