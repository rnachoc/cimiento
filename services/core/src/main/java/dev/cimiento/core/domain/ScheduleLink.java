package dev.cimiento.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "schedule_link")
public class ScheduleLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "from_code", nullable = false)
    private String fromCode;

    @Column(name = "to_code", nullable = false)
    private String toCode;

    private int lag;

    protected ScheduleLink() {
    }

    public ScheduleLink(Long scheduleId, String fromCode, String toCode, int lag) {
        this.scheduleId = scheduleId;
        this.fromCode = fromCode;
        this.toCode = toCode;
        this.lag = lag;
    }

    public String getFromCode() {
        return fromCode;
    }

    public String getToCode() {
        return toCode;
    }

    public int getLag() {
        return lag;
    }
}
