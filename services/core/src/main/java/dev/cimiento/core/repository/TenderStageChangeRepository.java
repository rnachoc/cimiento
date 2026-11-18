package dev.cimiento.core.repository;

import dev.cimiento.core.domain.TenderStageChange;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenderStageChangeRepository extends JpaRepository<TenderStageChange, Long> {

    List<TenderStageChange> findByTenderIdOrderById(Long tenderId);
}
