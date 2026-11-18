package dev.cimiento.core.repository;

import dev.cimiento.core.domain.ScheduleLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleLinkRepository extends JpaRepository<ScheduleLink, Long> {

    List<ScheduleLink> findByScheduleIdOrderByFromCodeAscToCodeAsc(Long scheduleId);
}
