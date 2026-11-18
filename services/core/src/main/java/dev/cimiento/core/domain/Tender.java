package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "contract_term_months", nullable = false)
    private int contractTermMonths;

    @Column(name = "planned_start", nullable = false)
    private LocalDate plannedStart;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Tender() {
    }

    public Tender(Project project, String code, String title, int contractTermMonths, LocalDate plannedStart) {
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

    public int getContractTermMonths() {
        return contractTermMonths;
    }

    public LocalDate getPlannedStart() {
        return plannedStart;
    }
}
