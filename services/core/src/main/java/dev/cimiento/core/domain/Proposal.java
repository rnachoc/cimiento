package dev.cimiento.core.domain;

import dev.cimiento.core.rules.ProposalType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class Proposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "tender_id")
    private Tender tender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProposalType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProposalStatus status;

    @Enumerated(EnumType.STRING)
    private ProposalType.Route route;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "executed_payload")
    private String executedPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String citations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String signals;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_results", nullable = false)
    private String ruleResults;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "routing_reasons", nullable = false)
    private String routingReasons;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected Proposal() {
    }

    public Proposal(Tender tender, ProposalType type, String payload, String citations, String signals,
                    String createdBy) {
        this.tender = tender;
        this.type = type;
        this.payload = payload;
        this.citations = citations;
        this.signals = signals;
        this.createdBy = createdBy;
    }

    public void applyEvaluation(ProposalStatus status, ProposalType.Route route, String ruleResults,
                                String routingReasons) {
        this.status = status;
        this.route = route;
        this.ruleResults = ruleResults;
        this.routingReasons = routingReasons;
    }

    public void markExecuted(String executedPayload) {
        this.status = ProposalStatus.EXECUTED;
        this.executedPayload = executedPayload;
        this.decidedAt = Instant.now();
    }

    public void markRejected() {
        this.status = ProposalStatus.REJECTED;
        this.decidedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Tender getTender() {
        return tender;
    }

    public ProposalType getType() {
        return type;
    }

    public ProposalStatus getStatus() {
        return status;
    }

    public ProposalType.Route getRoute() {
        return route;
    }

    public String getPayload() {
        return payload;
    }

    public String getExecutedPayload() {
        return executedPayload;
    }

    public String getCitations() {
        return citations;
    }

    public String getSignals() {
        return signals;
    }

    public String getRuleResults() {
        return ruleResults;
    }

    public String getRoutingReasons() {
        return routingReasons;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
