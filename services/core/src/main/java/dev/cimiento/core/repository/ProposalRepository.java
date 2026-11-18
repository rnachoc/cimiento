package dev.cimiento.core.repository;

import dev.cimiento.core.domain.Proposal;
import dev.cimiento.core.domain.ProposalStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProposalRepository extends JpaRepository<Proposal, Long> {

    List<Proposal> findByStatusOrderById(ProposalStatus status);

    List<Proposal> findAllByOrderById();

    List<Proposal> findByTenderIdOrderById(Long tenderId);
}
