package dev.cimiento.core.repository;

import dev.cimiento.core.domain.ScheduleEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepository extends JpaRepository<ScheduleEntity, Long> {

    Optional<ScheduleEntity> findFirstByTenderIdOrderByIdDesc(Long tenderId);
}
