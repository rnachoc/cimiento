package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A signed-in console session. The cookie holds a random id; only its SHA-256 is stored. */
@Entity
@Table(name = "person_session")
public class PersonSession {

    @Id
    @Column(name = "id_hash")
    private String idHash;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected PersonSession() {
    }

    public PersonSession(String idHash, Long personId, Instant expiresAt) {
        this.idHash = idHash;
        this.personId = personId;
        this.expiresAt = expiresAt;
    }

    public String getIdHash() {
        return idHash;
    }

    public Long getPersonId() {
        return personId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
