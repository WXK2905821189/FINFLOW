package com.finance.system.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Refuses unsafe production startup instead of falling back to development credentials. */
@Component
@Profile("prod")
public class ProductionSecurityValidator {
    private final JwtProperties jwtProperties;
    private final Environment environment;
    public ProductionSecurityValidator(JwtProperties jwtProperties, Environment environment) {
        this.jwtProperties = jwtProperties;
        this.environment = environment;
    }
    @PostConstruct
    void validate() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.length() < 32 || secret.startsWith("dev-only-")) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters in production");
        }
        require("spring.datasource.url", "DB_URL");
        require("spring.datasource.username", "DB_USERNAME");
        require("spring.datasource.password", "DB_PASSWORD");
    }
    private void require(String property, String variable) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalStateException(variable + " is required in production");
    }
}
