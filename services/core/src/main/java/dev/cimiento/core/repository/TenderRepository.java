package dev.cimiento.core.repository;

import dev.cimiento.core.domain.Tender;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TenderRepository extends JpaRepository<Tender, Long> {

    Optional<Tender> findByCode(String code);

    List<Tender> findByProjectIdOrderByCode(Long projectId);

    List<Tender> findAllByOrderByCode();

    /** Row lock so two stage moves of one tender cannot interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tender t where t.id = :id")
    Optional<Tender> lockById(Long id);
}
