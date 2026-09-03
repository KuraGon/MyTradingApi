package com.saamp.trading.provider;

import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.order.ExecutionGateRepository;
import com.saamp.trading.provider.pmx.PmxConnectException;
import com.saamp.trading.provider.pmx.PmxConnectJson;
import com.saamp.trading.provider.pmx.PmxTokenInfo;
import com.saamp.trading.provider.pmx.PmxTokenInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * StoneX PMXConnect v1_3 adapter based on production-observed read payloads.
 *
 * <p>Spot rates, positions and request-status resolution are implemented. Spot order submission
 * remains deliberately disabled until a UAT TokenID is available: the production token must never
 * be used to discover the /Trade response schema.</p>
 */
@Component
@ConditionalOnProperty(name = "trading.provider.mode", havingValue = "PMXCONNECT")
public class PmxConnectTradingProvider implements TradingProvider {
    private static final Logger log = LoggerFactory.getLogger(PmxConnectTradingProvider.class);
    private static final String PROVIDER = "PMXCONNECT";

    private final TradingProperties.Provider.Pmx config;
    private final ExecutionGateRepository executionGate;
    private final ProviderAccountRepository providerAccounts;
    private final PmxConnectJson json;
    private final HttpClient http;
    private final PmxTokenInfo tokenInfo;

    public PmxConnectTradingProvider(TradingProperties properties,
                                     ExecutionGateRepository executionGate,
                                     ProviderAccountRepository providerAccounts) {
        this.config = properties.getProvider().getPmx();
        this.executionGate = executionGate;
        this.providerAccounts = providerAccounts;
        this.json = new PmxConnectJson();
        validateConfiguration();
        this.tokenInfo = validateToken();
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.getRequestTimeout())
                .build();
    }

    @Override
    public String sourceName() { return PROVIDER; }

    @Override
    public boolean supportsSpotOrderSubmission() { return false; }

    @Override
    public List<MarketQuote> fetchSpotRates(Set<String> pairs) {
        // Production observation: MTL never includes FX and FOR never includes metals.
        // Both calls deliberately share one reference timestamp so local freshness is coherent.
        OffsetDateTime commonAsOf = OffsetDateTime.now(ZoneOffset.UTC);
        List<MarketQuote> metals = json.readSpotRates(get("GetSpotRates/MTL"), commonAsOf);
        List<MarketQuote> fx = json.readSpotRates(get("GetSpotRates/FOR"), commonAsOf);

        Map<String, MarketQuote> merged = new LinkedHashMap<>();
        metals.forEach(q -> merged.put(q.pair(), q));
        fx.forEach(q -> merged.put(q.pair(), q));

        if (pairs == null || pairs.isEmpty()) return List.copyOf(merged.values());
        Set<String> normalized = pairs.stream().map(p -> p.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        return merged.values().stream().filter(q -> normalized.contains(q.pair())).toList();
    }

    @Override
    public OrderAcknowledgement submitSpotOrder(SpotOrderRequest request) {
        throw new UnsupportedOperationException(
                "PMXConnect /Trade remains disabled until a UAT TokenID validates the exact success/InProcess payloads");
    }

    @Override
    public Optional<ExecutionReport> queryRequestStatus(String clientOrderId) {
        try {
            String body = post("GetRequestStatus", Map.of("ClOrdId", clientOrderId));
            ExecutionReport report = json.readRequestStatus(body, clientOrderId);
            log.debug("PMXConnect GetRequestStatus returned {} for ClOrdId {}", report.state(), clientOrderId);
            return Optional.of(report);
        } catch (PmxConnectException e) {
            if (e.isRequestNotFound631()) {
                // Only 631 is conclusive: PMXecute has no trace of this deterministic ClOrdId.
                log.warn("PMXConnect confirms unknown ClOrdId {} with error 631", clientOrderId);
                return Optional.of(new ExecutionReport(
                        ExecutionState.FAILED, clientOrderId, null, null, "631",
                        "Client Order ID does not exist; request was not transmitted"));
            }
            if (e.requiresImmediateAlert()) {
                log.error("PMXConnect GetRequestStatus requires immediate review: HTTP {}, code {}, ClOrdId {}",
                        e.httpStatus(), e.errorCode(), clientOrderId);
            }
            throw e;
        }
    }

    @Override
    public List<ProviderPosition> fetchPositions() {
        List<ProviderPosition> positions = json.readPositions(get("GetCmdtyPositions"));
        Set<String> accountCodes = positions.stream().map(ProviderPosition::accountCode).collect(java.util.stream.Collectors.toSet());
        if (accountCodes.size() > 1) {
            throw new IllegalStateException("GetCmdtyPositions returned multiple AccountCode values: " + accountCodes);
        }
        accountCodes.stream().findFirst().ifPresent(code -> providerAccounts.observe(PROVIDER, code, tokenInfo.clientId()));
        return positions;
    }

    private String get(String endpoint) {
        HttpRequest request = request(endpoint).GET().build();
        return execute(request);
    }

    private String post(String endpoint, Object payload) {
        HttpRequest request = request(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.write(payload)))
                .build();
        return execute(request);
    }

    private HttpRequest.Builder request(String endpoint) {
        return HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl(endpoint)))
                .timeout(config.getRequestTimeout())
                .header("Accept", "application/json")
                .header("TokenID", config.getTokenId());
    }

    private String execute(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
            var error = json.readError(response.statusCode(), response.body());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                executionGate.close("PMXCONNECT_AUTH_FAILURE");
                log.error("PMXConnect authentication/authorization failure: HTTP {}, code {}. Execution gate closed.",
                        response.statusCode(), error.errorCode());
            } else {
                log.warn("PMXConnect call failed: endpoint={}, HTTP={}, code={}",
                        request.uri().getPath(), response.statusCode(), error.errorCode());
            }
            String message = error.message() != null ? error.message() : error.errorMessage();
            throw new PmxConnectException(response.statusCode(), error.errorCode(), message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PmxConnectException(0, null, "PMXConnect request interrupted");
        } catch (IOException e) {
            log.warn("PMXConnect network call failed: endpoint={}, failure={}",
                    request.uri().getPath(), e.getClass().getSimpleName());
            throw new PmxConnectException(0, null, "PMXConnect network failure: " + e.getMessage());
        }
    }

    private String endpointUrl(String endpoint) {
        String base = stripTrailingSlash(config.getBaseUrl());
        String version = stripSlashes(config.getVersion());
        return base + "/" + version + "/" + stripLeadingSlash(endpoint);
    }

    private void validateConfiguration() {
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalStateException("PMXCONNECT_BASE_URL est obligatoire en mode PMXCONNECT");
        }
        if (config.getTokenId() == null || config.getTokenId().isBlank()) {
            throw new IllegalStateException("PMXCONNECT_TOKEN_ID est obligatoire en mode PMXCONNECT");
        }
        if (config.getEnvironment() == null || config.getEnvironment().isBlank()) {
            throw new IllegalStateException("PMXCONNECT_ENVIRONMENT est obligatoire en mode PMXCONNECT");
        }
    }

    private PmxTokenInfo validateToken() {
        PmxTokenInfo info = PmxTokenInspector.inspect(config.getTokenId(), json);
        if (info.environment() == null || !info.environment().equalsIgnoreCase(config.getEnvironment())) {
            throw new IllegalStateException("PMXConnect TokenID env=" + info.environment()
                    + " incompatible avec PMXCONNECT_ENVIRONMENT=" + config.getEnvironment());
        }
        Instant now = Instant.now();
        if (info.expiresAt() == null) {
            throw new IllegalStateException("PMXConnect TokenID sans claim exp exploitable");
        }
        if (!info.expiresAt().isAfter(now)) {
            throw new IllegalStateException("PMXConnect TokenID expiré depuis " + info.expiresAt());
        }
        Duration remaining = Duration.between(now, info.expiresAt());
        log.info("PMXConnect TokenID metadata: env={}, ClientId={}, expiresAt={}",
                info.environment(), info.clientId(), info.expiresAt());
        if (remaining.compareTo(config.getTokenExpiryWarning()) <= 0) {
            log.warn("PMXConnect TokenID expires in {} days ({})",
                    remaining.toDays(), info.expiresAt());
        }
        return info;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String stripLeadingSlash(String value) {
        String result = value;
        while (result.startsWith("/")) result = result.substring(1);
        return result;
    }

    private static String stripSlashes(String value) {
        return stripTrailingSlash(stripLeadingSlash(value));
    }
}
