package dev.cimiento.core.domain;

import dev.cimiento.core.rules.Rate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "rate")
public class RateEntity {

    @Id
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String activity;

    @Column(nullable = false)
    private String unit;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "rate_per_day", nullable = false)
    private BigDecimal ratePerDay;

    protected RateEntity() {
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Rate toRate() {
        return new Rate(code, activity, unit, unitPrice, ratePerDay);
    }
}
