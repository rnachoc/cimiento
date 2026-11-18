package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String key;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String role, String key, String requestHash, Long proposalId) {
        this.role = role;
        this.key = key;
        this.requestHash = requestHash;
        this.proposalId = proposalId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Long getProposalId() {
        return proposalId;
    }
}
