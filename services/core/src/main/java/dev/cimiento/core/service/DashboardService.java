package dev.cimiento.core.service;

import dev.cimiento.core.domain.Decision;
import dev.cimiento.core.repository.DecisionRepository;
import dev.cimiento.core.repository.ProposalRepository;
import dev.cimiento.core.repository.TenderStageChangeRepository;
import dev.cimiento.core.rules.Metrics;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Management figures: gathers the facts and lets {@link Metrics} count. */
@Service
public class DashboardService {

    /** Deadlines are calendar dates in Spain. */
    static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

    private final PortfolioService portfolio;
    private final TenderStageChangeRepository changes;
    private final ProposalRepository proposals;
    private final DecisionRepository decisions;
    private final Clock clock;

    public DashboardService(PortfolioService portfolio, TenderStageChangeRepository changes,
                            ProposalRepository proposals, DecisionRepository decisions) {
        this.portfolio = portfolio;
        this.changes = changes;
        this.proposals = proposals;
        this.decisions = decisions;
        this.clock = Clock.system(ZONE);
    }

    @Transactional(readOnly = true)
    public Metrics.Result dashboard() {
        var items = portfolio.portfolio();
        Map<Long, String> codes = items.stream()
                .collect(Collectors.toMap(PortfolioService.Item::tenderId, PortfolioService.Item::code));
        var tenders = items.stream().map(i -> new Metrics.TenderFacts(i.code(), i.stage(), i.createdAt(),
                i.bidDeadline(), i.budgetTotal())).toList();
        var moves = changes.findAll().stream().map(c -> new Metrics.Move(codes.get(c.getTenderId()), c.getFromStage(),
                c.getToStage(), c.getCreatedAt())).toList();
        // With four eyes a proposal can have two decisions (CORRECT, then APPROVE or REJECT): a final
        // rejection counts as REJECT, a correction that was then approved as CORRECT.
        Map<Long, Decision.Outcome> outcome = decisions.findAll().stream()
                .sorted(java.util.Comparator.comparing(Decision::getId))
                .collect(Collectors.toMap(Decision::getProposalId, Decision::getOutcome, DashboardService::merge));
        var facts = proposals.findAll().stream().map(p -> new Metrics.ProposalFacts(p.getType().name(),
                p.getStatus().name(), p.getRoute() == null ? null : p.getRoute().name(), p.getCreatedAt(),
                outcome.containsKey(p.getId()) ? outcome.get(p.getId()).name() : null)).toList();
        return Metrics.compute(tenders, moves, facts, clock.instant(), LocalDate.now(clock));
    }

    static Decision.Outcome merge(Decision.Outcome first, Decision.Outcome then) {
        if (then == Decision.Outcome.REJECT) {
            return Decision.Outcome.REJECT;
        }
        return first == Decision.Outcome.CORRECT ? Decision.Outcome.CORRECT : then;
    }
}
