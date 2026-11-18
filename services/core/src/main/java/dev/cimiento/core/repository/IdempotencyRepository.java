package dev.cimiento.core.repository;

import dev.cimiento.core.domain.IdempotencyRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByRoleAndKey(String role, String key);
}
