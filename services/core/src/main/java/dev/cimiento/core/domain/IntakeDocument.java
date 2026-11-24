package dev.cimiento.core.domain;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One uploaded PDF of an intake, stored as received (a trigger refuses UPDATE and DELETE). */
@Entity
@Table(name = "intake_document")
public class IntakeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intake_id", nullable = false, updatable = false)
    private Long intakeId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String sha256;

    @Column(nullable = false, updatable = false)
    private long size;

    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    private byte[] content;

    protected IntakeDocument() {
    }

    public IntakeDocument(Long intakeId, String name, String sha256, byte[] content) {
        this.intakeId = intakeId;
        this.name = name;
        this.sha256 = sha256;
        this.size = content.length;
        this.content = content;
    }

    public Long getIntakeId() {
        return intakeId;
    }

    public String getName() {
        return name;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSize() {
        return size;
    }

    public byte[] getContent() {
        return content;
    }
}
