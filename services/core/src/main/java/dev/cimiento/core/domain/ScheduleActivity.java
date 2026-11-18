package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "schedule_activity")
public class ScheduleActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private int duration;
    private int es;
    private int ef;
    private int ls;
    private int lf;

    @Column(name = "total_float")
    private int totalFloat;

    private boolean critical;

    protected ScheduleActivity() {
    }

    public ScheduleActivity(Long scheduleId, String code, String name, int duration, int es, int ef, int ls, int lf) {
        this.scheduleId = scheduleId;
        this.code = code;
        this.name = name;
        this.duration = duration;
        this.es = es;
        this.ef = ef;
        this.ls = ls;
        this.lf = lf;
        this.totalFloat = ls - es;
        this.critical = totalFloat == 0;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getDuration() {
        return duration;
    }

    public int getEs() {
        return es;
    }

    public int getEf() {
        return ef;
    }

    public int getLs() {
        return ls;
    }

    public int getLf() {
        return lf;
    }

    public int getTotalFloat() {
        return totalFloat;
    }

    public boolean isCritical() {
        return critical;
    }
}
