package dev.cimiento.core.security;

import dev.cimiento.core.rules.PersonRole;

/**
 * Who acts: the caller role and, for console calls with a session, the person behind it.
 * {@code person} and {@code personRole} are null for machines and SYSTEM.
 */
public record Actor(Role role, String person, PersonRole personRole) {

    public static Actor of(Role role) {
        return new Actor(role, null, null);
    }

    /** Text used in the audit hash: the role alone (as before phase 15) or role/username. */
    public String audit() {
        return person == null ? role.name() : role.name() + "/" + person;
    }
}
