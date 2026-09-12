package com.saamp.trading.security;

import com.saamp.trading.config.SecurityConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.test.context.ActiveProfiles("prod")
@WebMvcTest(DemoRouteAuthorizationIntegrationTest.FutureController.class)
@Import({SecurityConfig.class, DemoRouteAuthorizationIntegrationTest.FutureController.class})
@TestPropertySource(properties={"trading.demo.enabled=true", "trading.provider.mode=PMXCONNECT"})
class DemoRouteAuthorizationIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @ParameterizedTest
    @ValueSource(strings={"GET /api/v1/accounts/me", "GET /api/v1/accounts/me/balances",
        "GET /api/v1/accounts/me/positions", "GET /api/v1/accounts/me/summary", "GET /api/v1/accounts/me/prices",
        "GET /api/v1/accounts/me/orders", "GET /api/v1/accounts/me/orders/12", "GET /api/v1/accounts/me/statement",
        "POST /api/v1/accounts/me/orders/preview", "POST /api/v1/accounts/me/orders/12/submit"})
    void onlyExplicitDemoRoutesReachController(String route) throws Exception {
        perform(route,"DEMO",200);
    }

    @ParameterizedTest
    @ValueSource(strings={"POST /api/v1/accounts/me/transfers", "GET /api/v1/accounts/me/transfers",
        "POST /api/v1/accounts/me/config", "PATCH /api/v1/accounts/me", "POST /api/admin/commands",
        "POST /api/ingestion", "POST /api/reconciliation", "GET /api/provider/positions",
        "POST /api/maintenance", "GET /api/v1/accounts/2/balances", "GET /api/future-feature",
        "DELETE /api/v1/accounts/me/orders/12", "POST /api/v1/accounts/me/balances"})
    void demoDeniedBeforeAnyFutureOrLiveHandlerButLiveSecurityUnchanged(String route) throws Exception {
        perform(route,"DEMO",403);
        perform(route,"LIVE",200);
    }

    private void perform(String route,String mode,int expected) throws Exception {
        String[] pieces=route.split(" ");
        mvc.perform(request(HttpMethod.valueOf(pieces[0]),"/trading-api"+pieces[1]).contextPath("/trading-api")
            .with(jwt().jwt(j->j.claim("tradingMode",mode).claim("accessMode","DEMO".equals(mode)?"INTERNAL_DEMO":"CLIENT_SELF").claim("identityType","DEMO".equals(mode)?"INTERNAL":"CLIENT")))).andExpect(status().is(expected));
    }

    @RestController
    static class FutureController {
        @RequestMapping("/api/**") public String route() { return "reached"; }
    }
}
