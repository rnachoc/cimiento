package dev.cimiento.core.security;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Allow list of calls per role. Anything not listed is denied. AGENT proposes, REVIEWER decides,
 * ANALYST only reads.
 */
public final class AccessPolicy {

    private record Rule(String method, Pattern path, Set<Role> roles) {
    }

    private static final Set<Role> READERS = Set.of(Role.AGENT, Role.ANALYST, Role.REVIEWER);

    private static final List<Rule> RULES = List.of(
            new Rule("GET", Pattern.compile("/api/.*"), READERS),
            new Rule("POST", Pattern.compile("/api/proposals"), Set.of(Role.AGENT)),
            new Rule("POST", Pattern.compile("/api/proposals/\\d+/decision"), Set.of(Role.REVIEWER)),
            new Rule("POST", Pattern.compile("/api/projects"), Set.of(Role.REVIEWER)),
            new Rule("POST", Pattern.compile("/api/session"), Set.of(Role.REVIEWER)),
            new Rule("DELETE", Pattern.compile("/api/session"), Set.of(Role.REVIEWER)),
            new Rule("POST", Pattern.compile("/api/tenders"), Set.of(Role.AGENT, Role.REVIEWER)),
            new Rule("POST", Pattern.compile("/api/tenders/\\d+/stage"), Set.of(Role.REVIEWER)),
            new Rule("POST", Pattern.compile("/api/tenders/\\d+/stage/revert"), Set.of(Role.REVIEWER)),
            new Rule("POST", Pattern.compile("/api/tenders/\\d+/certifications"), Set.of(Role.REVIEWER)));

    private AccessPolicy() {
    }

    public static boolean allowed(Role role, String method, String path) {
        return RULES.stream().anyMatch(r -> r.method().equals(method) && r.path().matcher(path).matches()
                && r.roles().contains(role));
    }
}
