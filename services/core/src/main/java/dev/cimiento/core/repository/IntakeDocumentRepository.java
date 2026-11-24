package dev.cimiento.core.repository;

import dev.cimiento.core.domain.IntakeDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeDocumentRepository extends JpaRepository<IntakeDocument, Long> {

    List<IntakeDocument> findByIntakeIdOrderByName(Long intakeId);

    Optional<IntakeDocument> findByIntakeIdAndName(Long intakeId, String name);
}
