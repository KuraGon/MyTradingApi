package com.saamp.trading.provider;

import com.saamp.trading.config.TradingProperties;
import com.saamp.trading.domain.TradingMode;
import com.saamp.trading.order.ExecutionGateRepository;
import com.saamp.trading.provider.pmx.PmxConnectException;
import com.saamp.trading.provider.pmx.PmxConnectJson;
import com.saamp.trading.provider.pmx.PmxTokenInfo;
import com.saamp.trading.provider.pmx.PmxTokenInspector;
import com.saamp.trading.security.TradingDemoGuard;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>Spot rates, positions, request-status resolution and the MySAAMP-compatible direct
 * /Trade contract are implemented. Execution remains gated by the persisted execution gate.</p>
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
    private final TradingDemoGuard demoGuard;

    /** Test-compatible constructor; Spring uses the guarded constructor below. */
    public PmxConnectTradingProvider(TradingProperties properties,
                                     ExecutionGateRepository executionGate,
                                     ProviderAccountRepository providerAccounts) {
        this(properties, executionGate, providerAccounts, new TradingDemoGuard(properties));
    }

    @Autowired
    public PmxConnectTradingProvider(TradingProperties properties,
                                     ExecutionGateRepository executionGate,
                                     ProviderAccountRepository providerAccounts,
                                     TradingDemoGuard demoGuard) {
        this.config = properties.getProvider().getPmx();
        this.executionGate = executionGate;
        this.providerAccounts = providerAccounts;
        this.demoGuard = demoGuard;
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
    public boolean supportsSpotOrderSubmission() { return true; }

    @Override
    public List<MarketQuote> fetchSpotRates(Set<String> pairs) {
        if (pairs != null && !pairs.isEmpty()) {
            OffsetDateTime commonAsOf = OffsetDateTime.now(ZoneOffset.UTC);
            return pairs.stream().map(PmxConnectTradingProvider::canonicalPair).distinct().sorted()
                    .flatMap(pair -> json.readSpotRates(get("GetSpotRates/SPC/" + pair), commonAsOf).stream()
                            .filter(rate -> pair.equals(rate.pair())))
                    .toList();
        }
        throw new IllegalArgumentException("Explicit pairs are required for SPC pricing");
    }

    /** Lecture historique MTL/FOR sans caller de production ; conserve les contrats observes.
     * @return snapshot historique fusionne
     */
    @Deprecated
    public List<MarketQuote> fetchLegacyBulkSpotRates() {
        // Production observation: MTL never includes FX and FOR never includes metals.
        // Both calls deliberately share one reference timestamp so local freshness is coherent.
        OffsetDateTime commonAsOf = OffsetDateTime.now(ZoneOffset.UTC);
        List<MarketQuote> metals = json.readSpotRates(get("GetSpotRates/MTL"), commonAsOf);
        List<MarketQuote> fx = json.readSpotRates(get("GetSpotRates/FOR"), commonAsOf);

        Map<String, MarketQuote> merged = new LinkedHashMap<>();
        metals.forEach(q -> merged.put(q.pair(), q));
        fx.forEach(q -> merged.put(q.pair(), q));

        return List.copyOf(merged.values());
    }

    /** MySAAMP-compatible single-pair read endpoint, kept separate from the MTL/FOR bulk feed. */
    public Optional<MarketQuote> fetchSpotRate(String pair) {
        String canonicalPair = canonicalPair(pair);
        return fetchSpotRates(Set.of(canonicalPair)).stream().findFirst();
    }

    @Override
    public OrderAcknowledgement submitSpotOrder(SpotOrderRequest request) {
        validateSpotOrder(request);
        if (request.tradingMode() == TradingMode.DEMO) {
            throw new com.saamp.trading.common.TradingException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "DEMO_REAL_PROVIDER_FORBIDDEN",
                    "Un compte de démonstration ne peut pas transmettre d'ordre à un provider réel.");
        }
        // A second, provider-bound guard ensures a DEMO JWT cannot reach /Trade even if a future
        // controller path omits the mode passed to OrderExecutionService.
        demoGuard.assertRealProviderSubmissionAllowed();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ClOrdId", request.clientOrderId());
        payload.put("Pair", canonicalPair(request.pair()));
        payload.put("Deal", request.side() == com.saamp.trading.domain.OrderSide.BUY ? 1 : 2);
        payload.put("Quantity", request.quantityOz());
        payload.put("Remarks", remarks(request));
        return json.readTradeAcknowledgement(post("Trade", payload), request);
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

    private static void validateSpotOrder(SpotOrderRequest request) {
        if (request == null || request.clientOrderId() == null || request.clientOrderId().isBlank()
                || request.side() == null || request.quantityOz() == null
                || request.quantityOz().signum() <= 0) {
            throw new IllegalArgumentException("Requête SPOT PMXConnect invalide");
        }
        canonicalPair(request.pair());
    }

    private static String canonicalPair(String pair) {
        if (pair == null) throw new IllegalArgumentException("Paire SPOT PMXConnect invalide");
        String result = pair.trim().toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9]{6,16}")) {
            throw new IllegalArgumentException("Paire SPOT PMXConnect invalide");
        }
        return result;
    }

    private static String remarks(SpotOrderRequest request) {
        if (request.remarks() != null && !request.remarks().isBlank()) {
            return request.remarks().trim();
        }
        return "MyTrading " + request.clientOrderId();
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
        log.info("PMXConnect TokenID metadata: env={}, expiresAt={}",
                info.environment(), info.expiresAt());
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
