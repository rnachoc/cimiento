package dev.cimiento.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cimiento.core.domain.AuditEntry;
import dev.cimiento.core.rules.PersonRole;
import dev.cimiento.core.security.Actor;
import dev.cimiento.core.security.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditHashTest {

    private static final Instant AT = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    void rowsWithoutAPersonHashExactlyAsBeforePhase15() {
        // the pre-phase-15 hash used the role name alone
        String before = AuditService.hash(AuditService.GENESIS, AT, "REVIEWER", "DECISION_RECORDED", "proposal", "7", "{}");
        AuditEntry old = new AuditEntry(AT, "REVIEWER", null, "DECISION_RECORDED", "proposal", "7", "{}",
                AuditService.GENESIS, before);
        assertThat(old.getActorForHash()).isEqualTo(Actor.of(Role.REVIEWER).audit()).isEqualTo("REVIEWER");
    }

    @Test
    void thePersonIsPartOfTheHash() {
        Actor ana = new Actor(Role.REVIEWER, "ana", PersonRole.SITE_MANAGER);
        Actor luis = new Actor(Role.REVIEWER, "luis", PersonRole.SITE_MANAGER);
        String a = AuditService.hash(AuditService.GENESIS, AT, ana.audit(), "X", "proposal", "1", "{}");
        String l = AuditService.hash(AuditService.GENESIS, AT, luis.audit(), "X", "proposal", "1", "{}");
        assertThat(a).isNotEqualTo(l);
        AuditEntry e = new AuditEntry(AT, "REVIEWER", "ana", "X", "proposal", "1", "{}", AuditService.GENESIS, a);
        assertThat(e.getActorForHash()).isEqualTo("REVIEWER/ana");
    }
}
