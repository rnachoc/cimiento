package dev.cimiento.core.repository;

import dev.cimiento.core.domain.Decision;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<Decision, Long> {

    Optional<Decision> findByProposalId(Long proposalId);
}
