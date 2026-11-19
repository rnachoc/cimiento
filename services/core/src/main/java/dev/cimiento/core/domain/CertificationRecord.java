package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An issued monthly certification: a frozen snapshot of what the core calculated. */
@Entity
@Table(name = "certification")
public class CertificationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false, updatable = false)
    private Long tenderId;

    @Column(nullable = false, updatable = false)
    private int number;

    @Column(nullable = false, updatable = false)
    private String month;

    @Column(name = "cut_week", nullable = false, updatable = false)
    private int cutWeek;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String lines;

    @Column(nullable = false, updatable = false)
    private BigDecimal budget;

    @Column(name = "to_date", nullable = false, updatable = false)
    private BigDecimal toDate;

    @Column(nullable = false, updatable = false)
    private BigDecimal previous;

    @Column(nullable = false, updatable = false)
    private BigDecimal period;

    @Column(nullable = false, updatable = false)
    private BigDecimal retention;

    @Column(nullable = false, updatable = false)
    private BigDecimal net;

    @Column(nullable = false, updatable = false)
    private BigDecimal vat;

    @Column(nullable = false, updatable = false)
    private BigDecimal payable;

    @Column(name = "issued_by", nullable = false, updatable = false)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt = Instant.now();

    protected CertificationRecord() {
    }

    public CertificationRecord(Long tenderId, int number, String month, int cutWeek, String lines, BigDecimal budget,
                               BigDecimal toDate, BigDecimal previous, BigDecimal period, BigDecimal retention,
                               BigDecimal net, BigDecimal vat, BigDecimal payable, String issuedBy) {
        this.tenderId = tenderId;
        this.number = number;
        this.month = month;
        this.cutWeek = cutWeek;
        this.lines = lines;
        this.budget = budget;
        this.toDate = toDate;
        this.previous = previous;
        this.period = period;
        this.retention = retention;
        this.net = net;
        this.vat = vat;
        this.payable = payable;
        this.issuedBy = issuedBy;
    }

    public Long getId() {
        return id;
    }

    public Long getTenderId() {
        return tenderId;
    }

    public int getNumber() {
        return number;
    }

    public String getMonth() {
        return month;
    }

    public int getCutWeek() {
        return cutWeek;
    }

    public String getLines() {
        return lines;
    }

    public BigDecimal getBudget() {
        return budget;
    }

    public BigDecimal getToDate() {
        return toDate;
    }

    public BigDecimal getPrevious() {
        return previous;
    }

    public BigDecimal getPeriod() {
        return period;
    }

    public BigDecimal getRetention() {
        return retention;
    }

    public BigDecimal getNet() {
        return net;
    }

    public BigDecimal getVat() {
        return vat;
    }

    public BigDecimal getPayable() {
        return payable;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
