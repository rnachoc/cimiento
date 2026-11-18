package dev.cimiento.core.repository;

import dev.cimiento.core.domain.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /** Oldest unpublished events, locked so that two publishers never send the same batch. */
    @Query(value = "SELECT * FROM outbox_event WHERE published_at IS NULL ORDER BY id LIMIT :limit "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockUnpublished(int limit);

    long countByPublishedAtIsNull();
}
