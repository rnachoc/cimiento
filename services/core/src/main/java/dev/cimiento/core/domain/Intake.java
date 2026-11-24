package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** A tender uploaded by a person, waiting for (or done by) the agent worker. */
@Entity
@Table(name = "intake")
public class Intake {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private String code;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(name = "planned_start", nullable = false, updatable = false)
    private LocalDate plannedStart;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    @Column(nullable = false, updatable = false)
    private int documents;

    @Column(nullable = false, updatable = false)
    private long bytes;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "tender_id")
    private Long tenderId;

    @Column
    private Integer proposals;

    @Column
    private String message;

    protected Intake() {
    }

    public Intake(String code, String title, LocalDate plannedStart, int documents, long bytes, String uploadedBy) {
        this.code = code;
        this.title = title;
        this.plannedStart = plannedStart;
        this.documents = documents;
        this.bytes = bytes;
        this.uploadedBy = uploadedBy;
    }

    /** The worker took the job. */
    public void start() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
    }

    /** The worker reported back. */
    public void finish(boolean ok, Long tenderId, Integer proposals, String message) {
        this.status = ok ? Status.DONE : Status.FAILED;
        this.finishedAt = Instant.now();
        this.tenderId = tenderId;
        this.proposals = proposals;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getPlannedStart() {
        return plannedStart;
    }

    public Status getStatus() {
        return status;
    }

    public int getDocuments() {
        return documents;
    }

    public long getBytes() {
        return bytes;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getTenderId() {
        return tenderId;
    }

    public Integer getProposals() {
        return proposals;
    }

    public String getMessage() {
        return message;
    }
}
