package dev.cimiento.core.repository;

import dev.cimiento.core.domain.Tender;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenderRepository extends JpaRepository<Tender, Long> {

    Optional<Tender> findByCode(String code);

    List<Tender> findByProjectIdOrderByCode(Long projectId);
}
