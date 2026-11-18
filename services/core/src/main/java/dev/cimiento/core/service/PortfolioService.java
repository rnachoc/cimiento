package dev.cimiento.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cimiento.core.domain.Proposal;
import dev.cimiento.core.domain.ProposalStatus;
import dev.cimiento.core.domain.Tender;
import dev.cimiento.core.domain.TenderStageChange;
import dev.cimiento.core.repository.ProposalRepository;
import dev.cimiento.core.repository.TenderRepository;
import dev.cimiento.core.repository.TenderStageChangeRepository;
import dev.cimiento.core.rules.Pipeline;
import dev.cimiento.core.rules.ProposalType;
import dev.cimiento.core.rules.TenderStage;
import dev.cimiento.core.rules.Authority;
import dev.cimiento.core.security.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bid pipeline: one card per tender with its stage, submission deadline, bid/no-bid
 * recommendation and pending reviews; stage moves checked by {@link Pipeline} and audited.
 */
@Service
public class PortfolioService {

    /** A tender as the portfolio shows it. Deadline and recommendation come from the agents' proposals. */
    public record Item(Long tenderId, String code, String title, String projectCode, TenderStage stage,
                       Instant createdAt, Instant stageChangedAt, LocalDate bidDeadline, boolean deadlineApproved,
                       String recommendation, java.math.BigDecimal budgetTotal, int pendingReviews, int proposals,
                       boolean bidAnalysisApproved, List<TenderStage> next) {
    }

    private final TenderRepository tenders;
    private final ProposalRepository proposals;
    private final TenderStageChangeRepository changes;
    private final AuditService audit;
    private final CanonicalJson json;

    public PortfolioService(TenderRepository tenders, ProposalRepository proposals,
                            TenderStageChangeRepository changes, AuditService audit, CanonicalJson json) {
        this.tenders = tenders;
        this.proposals = proposals;
        this.changes = changes;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<Item> portfolio() {
        Map<Long, List<Proposal>> byTender = proposals.findAllByOrderById().stream()
                .collect(Collectors.groupingBy(p -> p.getTender().getId(), LinkedHashMap::new, Collectors.toList()));
        return tenders.findAllByOrderByCode().stream()
                .map(t -> item(t, byTender.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Item item(Long tenderId) {
        Tender t = tenders.findById(tenderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " not found"));
        return item(t, proposals.findByTenderIdOrderById(tenderId));
    }

    @Transactional(readOnly = true)
    public List<TenderStageChange> history(Long tenderId) {
        if (!tenders.existsById(tenderId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " not found");
        }
        return changes.findByTenderIdOrderById(tenderId);
    }

    @Transactional
    public Item move(Actor actor, Long tenderId, TenderStage to, String note) {
        if (actor.person() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "sign in as a person to move a tender");
        }
        List<Authority.Broken> broken = Authority.checkStage(actor.personRole(), to);
        if (!broken.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "stage change not allowed for " + actor.person(), broken);
        }
        Tender t = tenders.lockById(tenderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " not found"));
        List<Proposal> ps = proposals.findByTenderIdOrderById(tenderId);
        TenderStage from = t.getStage();
        String reason = note == null || note.isBlank() ? null : note.strip();
        List<String> errors = Pipeline.check(from, to, reason, facts(ps));
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "stage change not allowed", errors);
        }
        t.moveTo(to);
        changes.save(new TenderStageChange(t.getId(), from, to, reason, actor.role().name(),
                actor.person()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", t.getCode());
        details.put("from", from.name());
        details.put("to", to.name());
        audit.append(actor, "TENDER_STAGE_CHANGED", "tender", t.getId(), details, t.getProject().getCode());
        return item(t, ps);
    }

    private static Pipeline.Facts facts(List<Proposal> ps) {
        int pending = (int) ps.stream().filter(p -> p.getStatus() == ProposalStatus.PENDING_REVIEW).count();
        boolean approved = ps.stream().anyMatch(p -> p.getType() == ProposalType.BID_ANALYSIS
                && p.getStatus() == ProposalStatus.EXECUTED);
        return new Pipeline.Facts(pending, approved);
    }

    private Item item(Tender t, List<Proposal> ps) {
        Pipeline.Facts facts = facts(ps);
        Optional<Proposal> bid = latest(ps, ProposalType.BID_ANALYSIS, n -> true);
        LocalDate deadline = bid.map(p -> date(content(p).path("fields").path("fecha_presentacion").asText(null)))
                .orElse(null);
        String recommendation = latest(ps, ProposalType.REPORT,
                n -> "bid_no_bid".equals(n.path("kind").asText()))
                .map(p -> content(p).path("recommendation").asText(null))
                .orElse(null);
        java.math.BigDecimal budget = latest(ps, ProposalType.BUDGET, n -> true)
                .map(p -> decimal(content(p).path("claimedTotal").asText(null)))
                .orElse(null);
        return new Item(t.getId(), t.getCode(), t.getTitle(), t.getProject().getCode(), t.getStage(),
                t.getCreatedAt(), t.getStageChangedAt(), deadline,
                deadline != null && bid.get().getStatus() == ProposalStatus.EXECUTED, recommendation, budget,
                facts.pendingReviews(), ps.size(), facts.bidAnalysisApproved(),
                t.getStage().next().stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList());
    }

    /** Latest live proposal of a type (pending or executed), optionally filtered on its content. */
    private Optional<Proposal> latest(List<Proposal> ps, ProposalType type,
                                      java.util.function.Predicate<JsonNode> filter) {
        return ps.stream()
                .filter(p -> p.getType() == type)
                .filter(p -> p.getStatus() == ProposalStatus.PENDING_REVIEW || p.getStatus() == ProposalStatus.EXECUTED)
                .filter(p -> filter.test(content(p)))
                .max(Comparator.comparingLong(Proposal::getId));
    }

    /** What was executed (a correction, if any), otherwise what the agent proposed. */
    private JsonNode content(Proposal p) {
        JsonNode executed = json.read(p.getExecutedPayload());
        return executed != null && !executed.isNull() ? executed : json.read(p.getPayload());
    }

    private static java.math.BigDecimal decimal(String text) {
        try {
            return text == null ? null : new java.math.BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Latest live proposal of a type for one tender (pending or executed). */
    @Transactional(readOnly = true)
    public Optional<Proposal> latestLive(Long tenderId, ProposalType type) {
        return latest(proposals.findByTenderIdOrderById(tenderId), type, n -> true);
    }

    /** What was executed (a correction, if any), otherwise what the agent proposed. */
    public JsonNode contentOf(Proposal p) {
        return content(p);
    }

    private static LocalDate date(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
