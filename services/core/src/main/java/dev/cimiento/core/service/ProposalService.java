package dev.cimiento.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cimiento.core.domain.Decision;
import dev.cimiento.core.domain.IdempotencyRecord;
import dev.cimiento.core.domain.ProgressEntry;
import dev.cimiento.core.domain.Proposal;
import dev.cimiento.core.domain.ProposalStatus;
import dev.cimiento.core.domain.ScheduleActivity;
import dev.cimiento.core.domain.ScheduleEntity;
import dev.cimiento.core.domain.ScheduleLink;
import dev.cimiento.core.domain.Tender;
import dev.cimiento.core.repository.DecisionRepository;
import dev.cimiento.core.repository.IdempotencyRepository;
import dev.cimiento.core.repository.ProgressRepository;
import dev.cimiento.core.repository.ProposalRepository;
import dev.cimiento.core.repository.ScheduleActivityRepository;
import dev.cimiento.core.repository.ScheduleLinkRepository;
import dev.cimiento.core.repository.ScheduleRepository;
import dev.cimiento.core.repository.TenderRepository;
import dev.cimiento.core.rules.Cpm;
import dev.cimiento.core.rules.Payloads;
import dev.cimiento.core.rules.Payloads.Citation;
import dev.cimiento.core.rules.ProposalType;
import dev.cimiento.core.rules.ProposalType.Route;
import dev.cimiento.core.rules.RuleEngine;
import dev.cimiento.core.rules.RuleEngine.Evaluation;
import dev.cimiento.core.rules.RuleResult;
import dev.cimiento.core.rules.WorkCalendar;
import dev.cimiento.core.security.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proposals from agents: validated by the rule engine, routed to automatic execution or to a
 * person, executed only after the rules pass, and audited at every step.
 */
@Service
public class ProposalService {

    public record Submitted(Proposal proposal, boolean replayed) {
    }

    private final ProposalRepository proposals;
    private final TenderRepository tenders;
    private final IdempotencyRepository idempotency;
    private final DecisionRepository decisions;
    private final ScheduleRepository schedules;
    private final ScheduleActivityRepository activities;
    private final ScheduleLinkRepository links;
    private final ProgressRepository progress;
    private final RuleEngineProvider engines;
    private final AuditService audit;
    private final CanonicalJson json;
    private final ObjectMapper mapper;

    public ProposalService(ProposalRepository proposals, TenderRepository tenders, IdempotencyRepository idempotency,
                           DecisionRepository decisions, ScheduleRepository schedules,
                           ScheduleActivityRepository activities, ScheduleLinkRepository links,
                           ProgressRepository progress, RuleEngineProvider engines, AuditService audit,
                           CanonicalJson json, ObjectMapper mapper) {
        this.proposals = proposals;
        this.tenders = tenders;
        this.idempotency = idempotency;
        this.decisions = decisions;
        this.schedules = schedules;
        this.activities = activities;
        this.links = links;
        this.progress = progress;
        this.engines = engines;
        this.audit = audit;
        this.json = json;
        this.mapper = mapper;
    }

    @Transactional
    public Submitted submit(Role actor, String idempotencyKey, Long tenderId, ProposalType type, JsonNode payload,
                            List<Citation> citations, List<String> signals) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required (1-200 chars)");
        }
        List<Citation> cits = citations == null ? List.of() : citations;
        List<String> sigs = signals == null ? List.of() : signals;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tenderId", tenderId);
        request.put("type", type.name());
        request.put("payload", payload);
        request.put("citations", cits);
        request.put("signals", sigs);
        String requestHash = CanonicalJson.sha256(json.write(request));

        var previous = idempotency.findByRoleAndKey(actor.name(), idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "Idempotency-Key already used with another request");
            }
            return new Submitted(proposals.findById(previous.get().getProposalId()).orElseThrow(), true);
        }

        Tender tender = tenders.findById(tenderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " not found"));
        Evaluation eval = evaluate(tender, type, payload, cits, sigs);
        Proposal p = new Proposal(tender, type, json.write(payload), json.write(cits), json.write(sigs),
                actor.name());
        ProposalStatus status = eval.failed() ? ProposalStatus.RULES_FAILED
                : eval.route() == Route.AUTO ? ProposalStatus.EXECUTED : ProposalStatus.PENDING_REVIEW;
        p.applyEvaluation(status == ProposalStatus.EXECUTED ? ProposalStatus.PENDING_REVIEW : status, eval.route(),
                json.write(eval.results()), json.write(eval.reasons()));
        p = proposals.save(p);
        idempotency.save(new IdempotencyRecord(actor.name(), idempotencyKey, requestHash, p.getId()));
        audit.append(actor, "PROPOSAL_SUBMITTED", "proposal", p.getId(), summary(p, eval), key(p));
        if (status == ProposalStatus.EXECUTED) {
            execute(p, payload, eval, Role.SYSTEM);
        }
        return new Submitted(p, false);
    }

    @Transactional
    public Proposal decide(Role actor, Long proposalId, Decision.Outcome outcome, String comment,
                           JsonNode correctedPayload) {
        Proposal p = proposal(proposalId);
        if (p.getStatus() != ProposalStatus.PENDING_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "proposal " + proposalId + " is " + p.getStatus()
                    + ", only PENDING_REVIEW can be decided");
        }
        if (outcome != Decision.Outcome.CORRECT && correctedPayload != null && !correctedPayload.isNull()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "correctedPayload is only allowed with CORRECT");
        }
        JsonNode payload = json.read(p.getPayload());
        if (outcome == Decision.Outcome.CORRECT) {
            if (correctedPayload == null || correctedPayload.isNull()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CORRECT requires correctedPayload");
            }
            payload = correctedPayload;
        }
        decisions.save(new Decision(p.getId(), outcome, comment,
                outcome == Decision.Outcome.CORRECT ? json.write(correctedPayload) : null));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outcome", outcome.name());
        details.put("type", p.getType().name());
        if (outcome == Decision.Outcome.REJECT) {
            p.markRejected();
            audit.append(actor, "DECISION_RECORDED", "proposal", p.getId(), details, key(p));
            return p;
        }
        // Rules run again on what will actually be executed (the corrected payload, if any).
        Evaluation eval = evaluate(p.getTender(), p.getType(), payload, citations(p), signals(p));
        if (eval.failed()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "rules fail on the payload to execute",
                    failures(eval));
        }
        audit.append(actor, "DECISION_RECORDED", "proposal", p.getId(), details, key(p));
        execute(p, payload, eval, actor);
        return p;
    }

    @Transactional(readOnly = true)
    public Proposal proposal(Long id) {
        return proposals.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "proposal " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Proposal> list(ProposalStatus status, Long tenderId) {
        if (tenderId != null) {
            return proposals.findByTenderIdOrderById(tenderId).stream()
                    .filter(p -> status == null || p.getStatus() == status).toList();
        }
        return status == null ? proposals.findAllByOrderById() : proposals.findByStatusOrderById(status);
    }

    private Evaluation evaluate(Tender tender, ProposalType type, JsonNode payload, List<Citation> citations,
                                List<String> signals) {
        RuleEngine engine = engines.engine();
        return engine.evaluate(type, payload, citations, signals,
                new RuleEngine.TenderContext(tender.getPlannedStart(), tender.getContractTermMonths()));
    }

    private void execute(Proposal p, JsonNode payload, Evaluation eval, Role actor) {
        Tender tender = p.getTender();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", p.getType().name());
        try {
            switch (p.getType()) {
                case SCHEDULE -> {
                    Payloads.Schedule s = mapper.treeToValue(payload, Payloads.Schedule.class);
                    Cpm.Result cpm = eval.schedule();
                    ScheduleEntity entity = schedules.save(new ScheduleEntity(tender.getId(), p.getId(),
                            cpm.duration(), tender.getPlannedStart(),
                            WorkCalendar.workday(tender.getPlannedStart(), cpm.duration() - 1),
                            tender.getContractTermMonths() == null ? null
                                    : WorkCalendar.deadline(tender.getPlannedStart(), tender.getContractTermMonths())));
                    Map<String, String> names = s.activities().stream().collect(Collectors.toMap(
                            Cpm.ActivityInput::code, a -> a.name() == null ? a.code() : a.name()));
                    for (Cpm.ActivityTimes t : cpm.activities().values()) {
                        activities.save(new ScheduleActivity(entity.getId(), t.code(), names.get(t.code()),
                                t.duration(), t.es(), t.ef(), t.ls(), t.lf()));
                    }
                    for (Cpm.Link l : s.links()) {
                        links.save(new ScheduleLink(entity.getId(), l.from(), l.to(), l.lag()));
                    }
                    details.put("scheduleId", entity.getId());
                    details.put("durationDays", cpm.duration());
                    details.put("exceedsTerm", entity.getExceedsTerm());
                }
                case PROGRESS_UPDATE -> {
                    Payloads.Progress pr = mapper.treeToValue(payload, Payloads.Progress.class);
                    for (Payloads.ProgressRow row : pr.rows()) {
                        progress.save(new ProgressEntry(tender.getId(), p.getId(), pr.week(), row.activity(),
                                row.plannedPct(), row.actualPct()));
                    }
                    details.put("rows", pr.rows().size());
                }
                default -> {
                    if (eval.budgetTotal() != null) {
                        details.put("budgetTotal", eval.budgetTotal().toPlainString());
                    }
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("payload passed the rules but cannot be read", e);
        }
        p.markExecuted(json.write(payload));
        audit.append(actor, "PROPOSAL_EXECUTED", "proposal", p.getId(), details, key(p));
    }

    private List<Citation> citations(Proposal p) {
        return List.of(mapper.convertValue(json.read(p.getCitations()), Citation[].class));
    }

    private List<String> signals(Proposal p) {
        return List.of(mapper.convertValue(json.read(p.getSignals()), String[].class));
    }

    private static String key(Proposal p) {
        return p.getTender().getProject().getCode();
    }

    private static Map<String, Object> summary(Proposal p, Evaluation eval) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", p.getType().name());
        d.put("tender", p.getTender().getCode());
        d.put("status", p.getStatus().name());
        d.put("route", eval.route() == null ? null : eval.route().name());
        d.put("reasons", eval.reasons());
        d.put("rules", eval.results().stream()
                .collect(Collectors.toMap(RuleResult::rule, r -> r.outcome().name(), (a, b) -> a, LinkedHashMap::new)));
        return d;
    }

    private static List<Map<String, String>> failures(Evaluation eval) {
        return eval.results().stream().filter(r -> r.outcome() == RuleResult.Outcome.FAIL)
                .map(r -> Map.of("rule", r.rule(), "message", r.message())).toList();
    }
}
