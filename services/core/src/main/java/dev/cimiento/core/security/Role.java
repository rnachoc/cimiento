package dev.cimiento.core.security;

/** Callers are identified by a role key. SYSTEM is used only for automatic executions. */
public enum Role {
    AGENT,
    ANALYST,
    REVIEWER,
    SYSTEM
}
