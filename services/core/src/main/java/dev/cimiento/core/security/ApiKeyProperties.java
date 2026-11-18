package dev.cimiento.core.security;

import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Role keys from the environment (CIMIENTO_AGENT_KEY, CIMIENTO_ANALYST_KEY, CIMIENTO_REVIEWER_KEY). */
@ConfigurationProperties(prefix = "cimiento.keys")
public record ApiKeyProperties(String agent, String analyst, String reviewer) {

    private static final int MIN_LENGTH = 16;

    public ApiKeyProperties {
        List<String> keys = java.util.Arrays.asList(agent, analyst, reviewer);
        if (keys.stream().anyMatch(k -> k == null || k.length() < MIN_LENGTH)) {
            throw new IllegalStateException("role keys must be set and at least " + MIN_LENGTH + " characters");
        }
        if (new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalStateException("role keys must be different from each other");
        }
        if (keys.stream().anyMatch(k -> k.startsWith("replace-with"))) {
            throw new IllegalStateException("role keys still hold the .env.example placeholders");
        }
    }
}
