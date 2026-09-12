package com.saamp.trading.provider.pmx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.saamp.trading.provider.ExecutionReport;
import com.saamp.trading.provider.ExecutionState;
import com.saamp.trading.provider.MarketQuote;
import com.saamp.trading.provider.ProviderPosition;
import com.saamp.trading.provider.AcknowledgementState;
import com.saamp.trading.provider.OrderAcknowledgement;
import com.saamp.trading.provider.SpotOrderRequest;
import com.saamp.trading.domain.Asset;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dedicated JSON codec for PMXConnect.
 *
 * <p>Real PMXConnect responses are not casing-consistent: metals use {@code Pair/Ask/Bid}
 * while FX uses {@code PAIR/ASK/BID}. This mapper is therefore deliberately case-insensitive
 * and keeps floating-point values as {@link BigDecimal} from the first deserialization step.</p>
 */
public final class PmxConnectJson {

    private final ObjectMapper mapper;

    public PmxConnectJson() {
        this.mapper = JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Impossible de sérialiser la requête PMXConnect", e);
        }
    }

    public List<MarketQuote> readSpotRates(String json, OffsetDateTime commonAsOf) {
        try {
            SpotRatesEnvelope envelope = mapper.readValue(json, SpotRatesEnvelope.class);
            if (envelope.result() == null) return List.of();
            List<MarketQuote> result = new ArrayList<>();
            for (SpotRateDto rate : envelope.result()) {
                if (rate.pair() == null || rate.bid() == null || rate.ask() == null) {
                    throw new IllegalArgumentException("Réponse PMXConnect GetSpotRates incomplète");
                }
                result.add(new MarketQuote(rate.pair().toUpperCase(Locale.ROOT), rate.bid(), rate.ask(), null, commonAsOf));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Réponse PMXConnect GetSpotRates illisible", e);
        }
    }

    public List<ProviderPosition> readPositions(String json) {
        try {
            PositionsEnvelope envelope = mapper.readValue(json, PositionsEnvelope.class);
            if (envelope.result() == null) return List.of();
            List<ProviderPosition> result = new ArrayList<>();
            for (PositionDto position : envelope.result()) {
                if (position.accountCode() == null || position.cmdty() == null || position.position() == null) {
                    throw new IllegalArgumentException("Réponse PMXConnect GetCmdtyPositions incomplète");
                }
                result.add(new ProviderPosition(position.accountCode(), Asset.valueOf(position.cmdty().toUpperCase(Locale.ROOT)), position.position()));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Réponse PMXConnect GetCmdtyPositions illisible", e);
        }
    }

    public PmxError readError(int httpStatus, String json) {
        try {
            ErrorDto error = mapper.readValue(json, ErrorDto.class);
            return new PmxError(httpStatus, error.errorCode(), error.errorMsg(), error.message(), error.path());
        } catch (JsonProcessingException e) {
            return new PmxError(httpStatus, null, null, json == null ? null : abbreviate(json, 500), null);
        }
    }

    /**
     * Parses a successful GetRequestStatus response without binding the domain to PMX DTOs.
     * Known PMX fields are accepted case-insensitively; missing mandatory state fails closed.
     */
    public ExecutionReport readRequestStatus(String json, String fallbackClientOrderId) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode payload = unwrapResult(root);
            String stateText = firstText(payload, "Status", "RequestStatus", "State");
            if (stateText == null) {
                throw new IllegalArgumentException("GetRequestStatus ne contient aucun statut exploitable");
            }
            ExecutionState state = switch (stateText.trim().toUpperCase(Locale.ROOT)) {
                case "PROCESSED" -> ExecutionState.PROCESSED;
                case "INPROCESS", "IN_PROCESS" -> ExecutionState.IN_PROCESS;
                case "FAILED" -> ExecutionState.FAILED;
                default -> throw new IllegalArgumentException("Statut PMXConnect inconnu: " + stateText);
            };
            String clOrdId = firstText(payload, "ClOrdId", "ClientOrderId");
            String executionId = firstText(payload, "ExID", "ExId", "ExecutionId", "ExecId");
            BigDecimal fillPrice = firstDecimal(payload, "FillPrice", "Rate");
            String errorCode = firstText(payload, "ErrorCode", "error_code");
            String errorMessage = firstText(payload, "ErrorMessage", "ErrorMsg", "error_msg", "Message");
            return new ExecutionReport(state, clOrdId == null ? fallbackClientOrderId : clOrdId,
                    executionId, fillPrice, errorCode, errorMessage);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Réponse PMXConnect GetRequestStatus illisible", e);
        }
    }

    /** Parses the MySAAMP-compatible successful /Trade payload and fails closed. */
    public OrderAcknowledgement readTradeAcknowledgement(String json, SpotOrderRequest request) {
        try {
            JsonNode payload = unwrapResult(mapper.readTree(json));
            String returnedClOrdId = firstText(payload, "ClOrdId", "ClientOrderId");
            String executionId = firstText(payload, "EXID", "ExID", "ExecutionId", "ExecId");
            BigDecimal quantity = firstDecimal(payload, "Quantity");
            BigDecimal rate = firstDecimal(payload, "Rate");
            if (executionId == null || executionId.isBlank() || quantity == null || rate == null) {
                throw new IllegalArgumentException("Réponse PMXConnect Trade incomplète");
            }
            if (returnedClOrdId != null && !request.clientOrderId().equals(returnedClOrdId)) {
                throw new IllegalArgumentException("Réponse PMXConnect Trade associée à un autre ClOrdId");
            }
            if (quantity.compareTo(request.quantityOz()) != 0) {
                throw new IllegalArgumentException("Réponse PMXConnect Trade avec quantité incohérente");
            }
            return new OrderAcknowledgement(AcknowledgementState.FILLED, request.clientOrderId(), executionId,
                    rate, null, null);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Réponse PMXConnect Trade illisible", e);
        }
    }

    private static JsonNode unwrapResult(JsonNode root) {
        JsonNode result = findIgnoreCase(root, "result");
        if (result == null || result.isNull()) return root;
        if (result.isArray()) return result.isEmpty() ? result : result.get(0);
        return result;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = findIgnoreCase(node, name);
            if (value != null && !value.isNull()) return value.asText();
        }
        return null;
    }

    private static BigDecimal firstDecimal(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = findIgnoreCase(node, name);
            if (value != null && !value.isNull()) {
                if (value.isNumber()) return value.decimalValue();
                try { return new BigDecimal(value.asText()); } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private static JsonNode findIgnoreCase(JsonNode node, String name) {
        if (node == null || !node.isObject()) return null;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase(name)) return field.getValue();
        }
        return null;
    }

    private static String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private record SpotRatesEnvelope(List<SpotRateDto> result) {}
    private record SpotRateDto(String pair, BigDecimal ask, BigDecimal bid) {}
    private record PositionsEnvelope(List<PositionDto> result) {}
    private record PositionDto(String accountCode, String cmdty, BigDecimal position) {}

    private record ErrorDto(
            Integer status,
            String error,
            @com.fasterxml.jackson.annotation.JsonAlias({"error_code", "ErrorCode", "ERROR_CODE"}) Integer errorCode,
            @com.fasterxml.jackson.annotation.JsonAlias({"error_msg", "ErrorMsg", "ERROR_MSG"}) String errorMsg,
            String message,
            String path) {}

    public record PmxError(int httpStatus, Integer errorCode, String errorMessage, String message, String path) {}
}
