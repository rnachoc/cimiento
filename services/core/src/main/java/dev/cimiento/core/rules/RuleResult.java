package dev.cimiento.core.rules;

import java.util.Map;

/** Outcome of one rule. FAIL blocks execution; WARN is recorded and forces review. */
public record RuleResult(String rule, Outcome outcome, String message, Map<String, Object> details) {

    public enum Outcome { PASS, WARN, FAIL, SKIP }

    public static RuleResult pass(String rule, String message, Map<String, Object> details) {
        return new RuleResult(rule, Outcome.PASS, message, details);
    }

    public static RuleResult warn(String rule, String message, Map<String, Object> details) {
        return new RuleResult(rule, Outcome.WARN, message, details);
    }

    public static RuleResult fail(String rule, String message, Map<String, Object> details) {
        return new RuleResult(rule, Outcome.FAIL, message, details);
    }

    public static RuleResult skip(String rule, String message) {
        return new RuleResult(rule, Outcome.SKIP, message, Map.of());
    }
}
