package dev.cimiento.core.repository;

import dev.cimiento.core.domain.Decision;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<Decision, Long> {

    List<Decision> findByProposalIdOrderById(Long proposalId);
}
