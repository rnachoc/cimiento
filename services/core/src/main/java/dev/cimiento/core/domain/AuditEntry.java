package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** One append-only audit record. The database rejects updates and deletes. */
@Entity
@Immutable
@Table(name = "audit_entry")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "actor_role", nullable = false)
    private String actorRole;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(nullable = false)
    private String details;

    @Column(name = "prev_hash", nullable = false)
    private String prevHash;

    @Column(nullable = false)
    private String hash;

    protected AuditEntry() {
    }

    public AuditEntry(Instant createdAt, String actorRole, String action, String entityType, String entityId,
                      String details, String prevHash, String hash) {
        this.createdAt = createdAt;
        this.actorRole = actorRole;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public Long getSeq() {
        return seq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getDetails() {
        return details;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }
}
