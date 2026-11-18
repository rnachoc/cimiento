package dev.cimiento.core.repository;

import dev.cimiento.core.domain.PersonSession;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PersonSessionRepository extends JpaRepository<PersonSession, String> {

    @Modifying
    @Query("delete from PersonSession s where s.expiresAt < :now")
    int deleteExpired(Instant now);
}
