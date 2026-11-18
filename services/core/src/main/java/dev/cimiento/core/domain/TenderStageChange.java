package dev.cimiento.core.domain;

import dev.cimiento.core.rules.TenderStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One move of a tender through the pipeline, with the reviewer's reason. */
@Entity
@Table(name = "tender_stage_change")
public class TenderStageChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_stage", nullable = false)
    private TenderStage fromStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_stage", nullable = false)
    private TenderStage toStage;

    @Column
    private String note;

    @Column(name = "actor_role", nullable = false)
    private String actorRole;

    @Column
    private String person;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TenderStageChange() {
    }

    public TenderStageChange(Long tenderId, TenderStage fromStage, TenderStage toStage, String note, String actorRole,
                             String person) {
        this.person = person;
        this.tenderId = tenderId;
        this.fromStage = fromStage;
        this.toStage = toStage;
        this.note = note;
        this.actorRole = actorRole;
    }

    public Long getId() {
        return id;
    }

    public Long getTenderId() {
        return tenderId;
    }

    public TenderStage getFromStage() {
        return fromStage;
    }

    public TenderStage getToStage() {
        return toStage;
    }

    public String getNote() {
        return note;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getPerson() {
        return person;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
