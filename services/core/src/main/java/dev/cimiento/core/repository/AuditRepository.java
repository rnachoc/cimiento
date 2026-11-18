package dev.cimiento.core.repository;

import dev.cimiento.core.domain.AuditEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {

    Optional<AuditEntry> findTopByOrderBySeqDesc();

    List<AuditEntry> findAllByOrderBySeqAsc();

    List<AuditEntry> findBySeqGreaterThanOrderBySeqAsc(Long seq);
}
