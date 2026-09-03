package com.saamp.trading.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI tradingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SAAMP MyTrading Trading API")
                .version("0.2.0")
                .description("Trading client precious metals — Lot 2"));
    }
}
