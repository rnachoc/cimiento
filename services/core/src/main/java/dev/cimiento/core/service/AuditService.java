package dev.cimiento.core.service;

import dev.cimiento.core.domain.AuditEntry;
import dev.cimiento.core.domain.OutboxEvent;
import dev.cimiento.core.repository.AuditRepository;
import dev.cimiento.core.repository.OutboxRepository;
import dev.cimiento.core.security.Role;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit log with a hash chain. Each entry hashes the previous hash plus its own
 * fields, so changing or removing an entry breaks every later hash. Details carry identifiers,
 * statuses and counts, never document text.
 */
@Service
public class AuditService {

    public static final String GENESIS = "0".repeat(64);
    private static final long CHAIN_LOCK = 7_428_001L;

    public record Verification(boolean valid, int entries, Long firstBrokenSeq, String message) {
    }

    private final AuditRepository repository;
    private final OutboxRepository outbox;
    private final CanonicalJson json;
    private final EntityManager entityManager;

    public AuditService(AuditRepository repository, OutboxRepository outbox, CanonicalJson json,
                        EntityManager entityManager) {
        this.repository = repository;
        this.outbox = outbox;
        this.json = json;
        this.entityManager = entityManager;
    }

    /**
     * Appends an entry inside the caller's transaction (serialised with an advisory lock) and,
     * in the same transaction, an outbox event keyed by {@code key} (the project code) so that
     * Kafka keeps the order of each project's events.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEntry append(Role actor, String action, String entityType, Object entityId, Map<String, ?> details,
                             String key) {
        // pg_advisory_xact_lock returns void; wrap it so the query yields a plain integer
        entityManager.createNativeQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:k)) AS l")
                .setParameter("k", CHAIN_LOCK).getSingleResult();
        String prev = repository.findTopByOrderBySeqDesc().map(AuditEntry::getHash).orElse(GENESIS);
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String detailsJson = json.write(details);
        String id = String.valueOf(entityId);
        String hash = hash(prev, at, actor.name(), action, entityType, id, detailsJson);
        AuditEntry saved = repository.save(new AuditEntry(at, actor.name(), action, entityType, id, detailsJson, prev,
                hash));
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("seq", saved.getSeq());
        event.put("action", action);
        event.put("actor", actor.name());
        event.put("entityType", entityType);
        event.put("entityId", id);
        event.put("key", key);
        event.put("createdAt", at.toString());
        event.put("details", details);
        event.put("hash", hash);
        outbox.save(new OutboxEvent(key, action, json.write(event)));
        return saved;
    }

    @Transactional(readOnly = true)
    public Verification verify() {
        List<AuditEntry> entries = repository.findAllByOrderBySeqAsc();
        String prev = GENESIS;
        for (AuditEntry e : entries) {
            if (!e.getPrevHash().equals(prev)) {
                return new Verification(false, entries.size(), e.getSeq(), "previous hash does not match");
            }
            String expected = hash(prev, e.getCreatedAt(), e.getActorRole(), e.getAction(), e.getEntityType(),
                    e.getEntityId(), e.getDetails());
            if (!expected.equals(e.getHash())) {
                return new Verification(false, entries.size(), e.getSeq(), "hash does not match its content");
            }
            prev = e.getHash();
        }
        return new Verification(true, entries.size(), null, "chain intact");
    }

    static String hash(String prev, Instant at, String actor, String action, String entityType, String entityId,
                       String detailsJson) {
        return CanonicalJson.sha256(String.join("\n", prev, at.toString(), actor, action, entityType, entityId,
                detailsJson));
    }
}
