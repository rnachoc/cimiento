package dev.cimiento.core.repository;

import dev.cimiento.core.domain.CertificationRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CertificationRepository extends JpaRepository<CertificationRecord, Long> {

    List<CertificationRecord> findByTenderIdOrderByNumber(Long tenderId);
}
