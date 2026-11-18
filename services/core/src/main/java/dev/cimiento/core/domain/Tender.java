package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import dev.cimiento.core.rules.TenderStage;
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
import java.time.LocalDate;

@Entity
public class Tender {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String title;

    @Column(name = "contract_term_months")
    private Integer contractTermMonths;

    @Column(name = "planned_start", nullable = false)
    private LocalDate plannedStart;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenderStage stage = TenderStage.TO_REVIEW;

    @Column(name = "stage_changed_at")
    private Instant stageChangedAt;

    protected Tender() {
    }

    public Tender(Project project, String code, String title, Integer contractTermMonths, LocalDate plannedStart) {
        this.project = project;
        this.code = code;
        this.title = title;
        this.contractTermMonths = contractTermMonths;
        this.plannedStart = plannedStart;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    /** Null when the term was not found in the documents. */
    public Integer getContractTermMonths() {
        return contractTermMonths;
    }

    public LocalDate getPlannedStart() {
        return plannedStart;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TenderStage getStage() {
        return stage;
    }

    public Instant getStageChangedAt() {
        return stageChangedAt;
    }

    /** Only called after {@code Pipeline.check} allowed the move. */
    public void moveTo(TenderStage next) {
        this.stage = next;
        this.stageChangedAt = Instant.now();
    }
}
