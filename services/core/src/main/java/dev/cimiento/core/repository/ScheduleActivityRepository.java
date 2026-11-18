package dev.cimiento.core.repository;

import dev.cimiento.core.domain.ScheduleActivity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleActivityRepository extends JpaRepository<ScheduleActivity, Long> {

    List<ScheduleActivity> findByScheduleIdOrderByCode(Long scheduleId);
}
