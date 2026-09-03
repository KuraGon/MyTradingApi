package com.saamp.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point for the SAAMP MyTrading Trading API. */
@SpringBootApplication
@EnableScheduling
public class TradingApiApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(TradingApiApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(TradingApiApplication.class);
    }
}
