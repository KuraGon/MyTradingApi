package com.saamp.trading.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Builds a deterministic StoneX ClOrdId from the client idempotency key. */
public final class ClientOrderIdFactory {

    private ClientOrderIdFactory() {
    }

    public static String fromIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new IllegalArgumentException("idempotencyKey must contain 1 to 64 characters");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return "SAAMP-" + HexFormat.of().formatHex(digest, 0, 12).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
