package dev.cimiento.core.repository;

import dev.cimiento.core.domain.ProgressEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgressRepository extends JpaRepository<ProgressEntry, Long> {

    List<ProgressEntry> findByTenderIdOrderByWeekAscActivityCodeAsc(Long tenderId);
}
