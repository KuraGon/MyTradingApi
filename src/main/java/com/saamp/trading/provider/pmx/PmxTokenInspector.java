package com.saamp.trading.provider.pmx;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Decodes TokenID payload metadata only; it intentionally does not verify the provider signature. */
public final class PmxTokenInspector {
    private PmxTokenInspector() {}

    public static PmxTokenInfo inspect(String token, PmxConnectJson json) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("PMXCONNECT_TOKEN_ID est obligatoire");
        String[] parts = token.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("PMXCONNECT_TOKEN_ID n'est pas un JWT lisible");
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = json.mapper().readTree(new String(payload, StandardCharsets.UTF_8));
            String env = text(node, "env");
            String clientId = text(node, "ClientId");
            JsonNode expNode = node.get("exp");
            Instant expiresAt = expNode == null || !expNode.canConvertToLong() ? null : Instant.ofEpochSecond(expNode.asLong());
            return new PmxTokenInfo(env, clientId, expiresAt);
        } catch (Exception e) {
            throw new IllegalArgumentException("Impossible de décoder la charge utile du PMXCONNECT_TOKEN_ID", e);
        }
    }

    private static String text(JsonNode node, String name) {
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().equalsIgnoreCase(name)) return field.getValue().asText(null);
        }
        return null;
    }
}
