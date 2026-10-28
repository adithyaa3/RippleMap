package com.ripplemap.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI rippleMapOpenApi() {
        return new OpenAPI().info(new Info()
                .title("RippleMap API")
                .version("1.0")
                .description("Local REST API of RippleMap, the Java code impact analyzer. State-changing requests "
                        + "require the X-RippleMap-CSRF header issued to RippleMap pages, so they cannot be sent "
                        + "from this documentation page."));
    }
}
