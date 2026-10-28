package com.ripplemap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RippleMapApplication {

    public static void main(String[] args) {
        SpringApplication.run(RippleMapApplication.class, args);
    }
}
