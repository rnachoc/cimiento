package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** An executed (approved) schedule with its critical path computed by the core. */
@Entity
@Table(name = "schedule")
public class ScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "proposal_id", nullable = false, unique = true)
    private Long proposalId;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "contract_deadline")
    private LocalDate contractDeadline;

    @Column(name = "exceeds_term")
    private Boolean exceedsTerm;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ScheduleEntity() {
    }

    public ScheduleEntity(Long tenderId, Long proposalId, int durationDays, LocalDate startDate, LocalDate endDate,
                          LocalDate contractDeadline) {
        this.tenderId = tenderId;
        this.proposalId = proposalId;
        this.durationDays = durationDays;
        this.startDate = startDate;
        this.endDate = endDate;
        this.contractDeadline = contractDeadline;
        // unknown contract term: no deadline and no verdict
        this.exceedsTerm = contractDeadline == null ? null : endDate.isAfter(contractDeadline);
    }

    public Long getId() {
        return id;
    }

    public Long getTenderId() {
        return tenderId;
    }

    public Long getProposalId() {
        return proposalId;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalDate getContractDeadline() {
        return contractDeadline;
    }

    public Boolean getExceedsTerm() {
        return exceedsTerm;
    }
}
