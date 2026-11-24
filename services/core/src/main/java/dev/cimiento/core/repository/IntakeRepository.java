package dev.cimiento.core.repository;

import dev.cimiento.core.domain.Intake;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntakeRepository extends JpaRepository<Intake, Long> {

    boolean existsByCode(String code);

    List<Intake> findTop50ByOrderByIdDesc();

    /**
     * The oldest job a worker may take, locked so two workers never take the same one: queued, or
     * running for so long that its worker is presumed gone.
     */
    @Query(value = "SELECT * FROM intake WHERE status = 'QUEUED' OR (status = 'RUNNING' AND started_at < :stale) "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Intake> nextForWorker(@Param("stale") Instant stale);

}
