package com.saamp.trading.api;

import com.saamp.trading.account.*;
import com.saamp.trading.common.*;
import com.saamp.trading.config.*;
import com.saamp.trading.domain.*;
import com.saamp.trading.security.*;
import com.saamp.trading.reservation.ReservationService;
import com.saamp.trading.risk.RiskService;
import com.saamp.trading.pricing.PricingService;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@ActiveProfiles("prod")
@WebMvcTest({PlatformStatusController.class, AccountController.class})
@Import({SecurityConfig.class,ApiExceptionHandler.class,CurrentTraderService.class,TradingDemoGuard.class,
        AccountService.class,TradingProperties.class,PlatformAvailabilityService.class})
@TestPropertySource(properties={"trading.demo.enabled=true","trading.provider.mode=PMXCONNECT"})
class PlatformStatusControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AccountRepository accounts;
    @MockitoBean EffectiveBalanceService balances;
    @MockitoBean PositionService positions;
    @MockitoBean RiskService risk;
    @MockitoBean ReservationService reservations;
    @MockitoBean PricingService pricing;
    static final String PATH="/api/v1/platform/status";
    TradingAccount account(TradingMode mode) {
        var account=new TradingAccount(1,2,Asset.EUR,AccountStatus.ACTIVE,null,null,null,null,null,null,
                1,OffsetDateTime.now(),OffsetDateTime.now(),mode);
        when(accounts.findByCompanyId(2)).thenReturn(Optional.of(account));
        return account;
    }
    JwtRequestPostProcessor token(TradingMode mode) {
        boolean demo=mode==TradingMode.DEMO;
        return jwt().jwt(j->j.subject("99").claim("companyId",2L).claim("tradingMode",mode.name())
                .claim("identityType",demo?"INTERNAL":"CLIENT").claim("accessMode",demo?"INTERNAL_DEMO":"CLIENT_SELF"))
                .authorities(new SimpleGrantedAuthority("MYTRADING_ACCESS"),new SimpleGrantedAuthority("MYTRADING_ACCOUNT_READ"));
    }
    @Test void liveStatusRecoversWithoutRestartAndNeverExposesTechnicalDetails() throws Exception {
        var account=account(TradingMode.LIVE);
        when(balances.officialAvailable(account)).thenReturn(false,true);
        mvc.perform(get(PATH).with(token(TradingMode.LIVE))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value("TECHNICAL_CLOSURE"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(30))
                .andExpect(jsonPath("$.checkedAt").isString()).andExpect(jsonPath("$.*",hasSize(3)));
        mvc.perform(get(PATH).with(token(TradingMode.LIVE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(nullValue()))
                .andExpect(jsonPath("$.*",hasSize(3)));
    }
    @Test void prodDemoRemainsOpenAndDoesNotAskForOfficialState() throws Exception {
        account(TradingMode.DEMO);
        when(balances.officialAvailable(any())).thenThrow(new IllegalStateException("unreachable fixture"));
        mvc.perform(get(PATH).with(token(TradingMode.DEMO))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(nullValue()));
        verifyNoInteractions(balances);
    }
    @ParameterizedTest @ValueSource(strings={"OFFICIAL_BALANCE_UNAVAILABLE","OFFICIAL_CURRENCY_UNSUPPORTED"})
    void liveBusinessFailureIsSanitizedEvenIfClientSkippedStatus(String internalCode) throws Exception {
        account(TradingMode.LIVE);
        when(balances.findAll(1,TradingMode.LIVE)).thenThrow(new TradingException(HttpStatus.SERVICE_UNAVAILABLE,
                internalCode,"AS400 DB2 JDBC internal-host timeout table", Map.of("dependency","internal-host")));
        mvc.perform(get("/api/v1/accounts/me/balances").with(token(TradingMode.LIVE)))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","30"))
                .andExpect(jsonPath("$.code").value("PLATFORM_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("La plateforme est momentanément indisponible pour des raisons techniques."))
                .andExpect(jsonPath("$.*",hasSize(2)))
                .andExpect(content().string(not(containsString("AS400"))))
                .andExpect(content().string(not(containsString("internal-host"))));
    }
    @Test void tokenAccountModeMismatchIsStillForbidden() throws Exception {
        account(TradingMode.LIVE);
        mvc.perform(get(PATH).with(token(TradingMode.DEMO))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_TRADING_MODE_MISMATCH"));
        verifyNoInteractions(balances);
    }
    @Test void authenticationAndPermissionRemainRequired() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority("UNRELATED")))).andExpect(status().isForbidden());
        verifyNoInteractions(balances);
    }
    @Test void demoCannotPostToStatusOrDiscoverOtherAccounts() throws Exception {
        mvc.perform(post(PATH).with(token(TradingMode.DEMO))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/platform/status/2").with(token(TradingMode.DEMO))).andExpect(status().isForbidden());
        verifyNoInteractions(balances);
    }
}
