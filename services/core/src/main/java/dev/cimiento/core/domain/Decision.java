package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class Decision {

    public enum Outcome { APPROVE, CORRECT, REJECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposal_id", nullable = false, unique = true)
    private Long proposalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    private String comment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "corrected_payload")
    private String correctedPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Decision() {
    }

    public Decision(Long proposalId, Outcome outcome, String comment, String correctedPayload) {
        this.proposalId = proposalId;
        this.outcome = outcome;
        this.comment = comment;
        this.correctedPayload = correctedPayload;
    }

    public Long getId() {
        return id;
    }

    public Long getProposalId() {
        return proposalId;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
