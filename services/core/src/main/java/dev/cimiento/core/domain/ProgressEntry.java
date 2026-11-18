package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Weekly progress of one activity (avance), recorded from an executed PROGRESS_UPDATE. */
@Entity
@Table(name = "progress")
public class ProgressEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    private int week;

    @Column(name = "activity_code", nullable = false)
    private String activityCode;

    @Column(name = "planned_pct")
    private int plannedPct;

    @Column(name = "actual_pct")
    private int actualPct;

    protected ProgressEntry() {
    }

    public ProgressEntry(Long tenderId, Long proposalId, int week, String activityCode, int plannedPct,
                         int actualPct) {
        this.tenderId = tenderId;
        this.proposalId = proposalId;
        this.week = week;
        this.activityCode = activityCode;
        this.plannedPct = plannedPct;
        this.actualPct = actualPct;
    }

    public int getWeek() {
        return week;
    }

    public String getActivityCode() {
        return activityCode;
    }

    public int getPlannedPct() {
        return plannedPct;
    }

    public int getActualPct() {
        return actualPct;
    }
}
