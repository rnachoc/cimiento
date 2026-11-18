package dev.cimiento.core.rules;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cimiento.core.rules.Payloads.Budget;
import dev.cimiento.core.rules.Payloads.BudgetLine;
import dev.cimiento.core.rules.Payloads.Citation;
import dev.cimiento.core.rules.Payloads.Progress;
import dev.cimiento.core.rules.Payloads.ProgressRow;
import dev.cimiento.core.rules.Payloads.RiskSignal;
import dev.cimiento.core.rules.Payloads.Schedule;
import dev.cimiento.core.rules.Payloads.Severity;
import dev.cimiento.core.rules.Payloads.Takeoff;
import dev.cimiento.core.rules.Payloads.TakeoffItem;
import dev.cimiento.core.rules.Payloads.TakeoffRow;
import dev.cimiento.core.rules.ProposalType.Route;
import dev.cimiento.core.rules.RuleResult.Outcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic rules applied to every proposal, always in this order:
 *
 * <ol>
 *   <li>PAYLOAD: the payload parses into the type's schema and basic ranges hold.</li>
 *   <li>CITATIONS: at least one citation, each with document, page &ge; 1 and text.</li>
 *   <li>TAKEOFF_UNITS_AND_SUMS: known units, rows convert to the item's canonical unit, the sum of
 *       non-duplicate rows equals the item quantity, duplicates really repeat a row.</li>
 *   <li>BUDGET: amount = quantity x company price (cents, half up); claimed total must match.</li>
 *   <li>SCHEDULE_GRAPH: positive durations, known activities, no duplicates, no cycles.</li>
 *   <li>CRITICAL_PATH: CPM, deterministic.</li>
 *   <li>CONTRACT_TERM: the schedule end against the contract deadline (WARN when exceeded).</li>
 *   <li>REVIEW_THRESHOLD: amount, risk and injection signals that force human review.</li>
 * </ol>
 *
 * Any FAIL blocks execution. Otherwise the route is HUMAN when the type defaults to it, when a
 * rule warns or when a threshold reason applies; AUTO only for record-only types with no reason.
 */
public final class RuleEngine {

    public static final String SIGNAL_INJECTION = "INJECTION_DETECTED";
    private static final BigDecimal CENT = new BigDecimal("0.01");

    /** {@code contractTermMonths} is null when the term is unknown. */
    public record TenderContext(LocalDate plannedStart, Integer contractTermMonths) {
    }

    public record Evaluation(List<RuleResult> results, Route route, List<String> reasons,
                             Cpm.Result schedule, BigDecimal budgetTotal) {
        public boolean failed() {
            return results.stream().anyMatch(r -> r.outcome() == Outcome.FAIL);
        }
    }

    private final Map<String, Rate> rates;
    private final BigDecimal amountThreshold;
    private final ObjectMapper mapper;

    public RuleEngine(Map<String, Rate> rates, BigDecimal amountThreshold, ObjectMapper mapper) {
        this.rates = Map.copyOf(rates);
        this.amountThreshold = amountThreshold;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public Evaluation evaluate(ProposalType type, JsonNode payload, List<Citation> citations,
                               List<String> signals, TenderContext tender) {
        List<RuleResult> results = new ArrayList<>();
        Object parsed = null;
        try {
            parsed = parse(type, payload);
            results.add(RuleResult.pass("PAYLOAD", "payload matches " + type, Map.of()));
        } catch (Exception e) {
            results.add(RuleResult.fail("PAYLOAD", "invalid " + type + " payload: " + rootMessage(e),
                    Map.of()));
        }
        results.add(citations(citations));

        BigDecimal budgetTotal = null;
        Cpm.Result cpm = null;
        if (parsed instanceof Takeoff t) {
            results.add(takeoff(t));
        } else {
            results.add(RuleResult.skip("TAKEOFF_UNITS_AND_SUMS", "not a takeoff"));
        }
        if (parsed instanceof Budget b) {
            RuleResult r = budget(b);
            results.add(r);
            if (r.outcome() == Outcome.PASS) {
                budgetTotal = new BigDecimal((String) r.details().get("total"));
            }
        } else {
            results.add(RuleResult.skip("BUDGET", "not a budget"));
        }
        if (parsed instanceof Schedule s) {
            List<String> errors = Cpm.validate(s.activities(), s.links());
            if (errors.isEmpty()) {
                results.add(RuleResult.pass("SCHEDULE_GRAPH", "valid acyclic graph", Map.of()));
                cpm = Cpm.solve(s.activities(), s.links());
                results.add(RuleResult.pass("CRITICAL_PATH", "duration " + cpm.duration() + " working days",
                        Map.of("duration", cpm.duration(), "critical", cpm.critical())));
                results.add(contractTerm(cpm, tender));
            } else {
                results.add(RuleResult.fail("SCHEDULE_GRAPH", String.join("; ", errors),
                        Map.of("errors", errors)));
                results.add(RuleResult.skip("CRITICAL_PATH", "invalid graph"));
                results.add(RuleResult.skip("CONTRACT_TERM", "invalid graph"));
            }
        } else {
            results.add(RuleResult.skip("SCHEDULE_GRAPH", "not a schedule"));
            results.add(RuleResult.skip("CRITICAL_PATH", "not a schedule"));
            results.add(RuleResult.skip("CONTRACT_TERM", "not a schedule"));
        }

        List<String> reasons = thresholdReasons(parsed, signals, budgetTotal);
        results.add(reasons.isEmpty()
                ? RuleResult.pass("REVIEW_THRESHOLD", "no threshold reached", Map.of())
                : RuleResult.warn("REVIEW_THRESHOLD", "review required", Map.of("reasons", reasons)));

        boolean failed = results.stream().anyMatch(r -> r.outcome() == Outcome.FAIL);
        Route route = null;
        if (!failed) {
            boolean warned = results.stream().anyMatch(r -> r.outcome() == Outcome.WARN);
            route = type.defaultRoute() == Route.HUMAN || warned ? Route.HUMAN : Route.AUTO;
        }
        List<String> allReasons = new ArrayList<>(reasons);
        results.stream().filter(r -> r.outcome() == Outcome.WARN && !r.rule().equals("REVIEW_THRESHOLD"))
                .forEach(r -> allReasons.add(r.rule()));
        if (route == Route.HUMAN && type.defaultRoute() == Route.HUMAN) {
            allReasons.add(0, "TYPE_REQUIRES_REVIEW");
        }
        return new Evaluation(List.copyOf(results), route, List.copyOf(allReasons), cpm, budgetTotal);
    }

    private Object parse(ProposalType type, JsonNode payload) throws Exception {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        return switch (type) {
            case TAKEOFF -> checkTakeoff(mapper.treeToValue(payload, Takeoff.class));
            case BUDGET -> checkBudget(mapper.treeToValue(payload, Budget.class));
            case SCHEDULE -> checkSchedule(mapper.treeToValue(payload, Schedule.class));
            case PROGRESS_UPDATE -> checkProgress(mapper.treeToValue(payload, Progress.class));
            case RISK_SIGNAL -> checkRisk(mapper.treeToValue(payload, RiskSignal.class));
            case BID_ANALYSIS, DOCUMENT_DRAFT, REPORT -> payload;
        };
    }

    private static Takeoff checkTakeoff(Takeoff t) {
        require(t.items() != null && !t.items().isEmpty(), "items required");
        for (TakeoffItem i : t.items()) {
            require(i != null, "item required");
            require(i.code() != null && i.unit() != null && i.quantity() != null, "item fields required");
            require(i.rows() != null && !i.rows().isEmpty(), "rows required for " + i.code());
            for (TakeoffRow r : i.rows()) {
                require(r != null && r.unitText() != null && r.quantity() != null, "row fields required");
                require(r.quantity().signum() >= 0, "negative quantity in " + i.code());
            }
        }
        return t;
    }

    private static Budget checkBudget(Budget b) {
        require(b.lines() != null && !b.lines().isEmpty(), "lines required");
        for (BudgetLine l : b.lines()) {
            require(l != null && l.code() != null && l.quantity() != null, "line fields required");
            require(l.quantity().signum() >= 0, "negative quantity in " + l.code());
        }
        return b;
    }

    private static Schedule checkSchedule(Schedule s) {
        require(s.activities() != null && s.activities().stream().allMatch(java.util.Objects::nonNull),
                "activities required");
        require(s.links() != null && s.links().stream().allMatch(java.util.Objects::nonNull), "links required");
        return s;
    }

    private static Progress checkProgress(Progress p) {
        require(p.week() > 0, "week must be positive");
        require(p.rows() != null && !p.rows().isEmpty(), "rows required");
        for (ProgressRow r : p.rows()) {
            require(r != null && r.activity() != null, "activity required");
            require(r.plannedPct() >= 0 && r.plannedPct() <= 100 && r.actualPct() >= 0 && r.actualPct() <= 100,
                    "percentages must be between 0 and 100");
        }
        return p;
    }

    private static RiskSignal checkRisk(RiskSignal r) {
        require(r.kind() != null && r.severity() != null && r.description() != null, "risk fields required");
        return r;
    }

    private static RuleResult citations(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return RuleResult.fail("CITATIONS", "nothing runs without a citation", Map.of());
        }
        for (Citation c : citations) {
            if (c == null || c.document() == null || c.document().isBlank() || c.page() == null
                    || c.page() < 1 || c.text() == null || c.text().isBlank()) {
                return RuleResult.fail("CITATIONS", "each citation needs document, page >= 1 and text",
                        Map.of());
            }
        }
        return RuleResult.pass("CITATIONS", citations.size() + " citation(s)", Map.of());
    }

    private RuleResult takeoff(Takeoff t) {
        List<String> errors = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (TakeoffItem item : t.items()) {
            if (!codes.add(item.code())) {
                errors.add("duplicate item " + item.code());
            }
            Rate rate = rates.get(item.code());
            if (rate == null) {
                errors.add("unknown work item " + item.code());
                continue;
            }
            if (!rate.unit().equals(item.unit())) {
                errors.add(item.code() + " must be measured in " + rate.unit() + ", not " + item.unit());
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            List<TakeoffRow> counted = new ArrayList<>();
            for (TakeoffRow row : item.rows()) {
                var conv = Units.lookup(row.unitText());
                if (conv.isEmpty()) {
                    errors.add(item.code() + ": unknown unit '" + row.unitText() + "'");
                    continue;
                }
                if (!conv.get().canonical().equals(item.unit())) {
                    errors.add(item.code() + ": unit '" + row.unitText() + "' is not " + item.unit());
                    continue;
                }
                if (row.isDuplicate()) {
                    boolean repeats = counted.stream().anyMatch(c -> c.unitText().equals(row.unitText())
                            && c.quantity().compareTo(row.quantity()) == 0);
                    if (!repeats) {
                        errors.add(item.code() + ": row marked duplicate does not repeat an earlier row");
                    }
                    continue;
                }
                counted.add(row);
                sum = sum.add(row.quantity().multiply(conv.get().factor()));
            }
            if (sum.subtract(item.quantity()).abs().compareTo(CENT) > 0) {
                errors.add(item.code() + ": rows add up to " + sum.stripTrailingZeros().toPlainString()
                        + " but the item says " + item.quantity().toPlainString());
            }
        }
        return errors.isEmpty()
                ? RuleResult.pass("TAKEOFF_UNITS_AND_SUMS", t.items().size() + " item(s) consistent", Map.of())
                : RuleResult.fail("TAKEOFF_UNITS_AND_SUMS", String.join("; ", errors), Map.of("errors", errors));
    }

    private RuleResult budget(Budget b) {
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (BudgetLine line : b.lines()) {
            if (!codes.add(line.code())) {
                errors.add("duplicate line " + line.code());
            }
            Rate rate = rates.get(line.code());
            if (rate == null) {
                errors.add("unknown work item " + line.code());
                continue;
            }
            BigDecimal amount = line.quantity().multiply(rate.unitPrice()).setScale(2, RoundingMode.HALF_UP);
            total = total.add(amount);
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("code", line.code());
            l.put("quantity", line.quantity().toPlainString());
            l.put("unitPrice", rate.unitPrice().toPlainString());
            l.put("amount", amount.toPlainString());
            lines.add(l);
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        if (b.claimedTotal() != null && b.claimedTotal().compareTo(total) != 0) {
            errors.add("claimed total " + b.claimedTotal().toPlainString() + " differs from computed "
                    + total.toPlainString());
        }
        if (!errors.isEmpty()) {
            return RuleResult.fail("BUDGET", String.join("; ", errors), Map.of("errors", errors));
        }
        return RuleResult.pass("BUDGET", "total " + total.toPlainString() + " EUR (VAT excluded)",
                Map.of("total", total.toPlainString(), "lines", lines));
    }

    private static RuleResult contractTerm(Cpm.Result cpm, TenderContext tender) {
        if (tender.plannedStart().getDayOfWeek() != DayOfWeek.MONDAY) {
            return RuleResult.fail("CONTRACT_TERM", "planned start must be a Monday", Map.of());
        }
        LocalDate end = WorkCalendar.workday(tender.plannedStart(), cpm.duration() - 1);
        if (tender.contractTermMonths() == null) {
            return RuleResult.warn("CONTRACT_TERM", "contract term unknown; schedule ends " + end,
                    Map.of("end", end.toString(), "termKnown", false));
        }
        LocalDate deadline = WorkCalendar.deadline(tender.plannedStart(), tender.contractTermMonths());
        Map<String, Object> details = Map.of("end", end.toString(), "deadline", deadline.toString(),
                "exceeds", end.isAfter(deadline));
        return end.isAfter(deadline)
                ? RuleResult.warn("CONTRACT_TERM", "schedule ends " + end + ", after the contract deadline "
                        + deadline, details)
                : RuleResult.pass("CONTRACT_TERM", "schedule ends " + end + ", deadline " + deadline, details);
    }

    private List<String> thresholdReasons(Object parsed, List<String> signals, BigDecimal budgetTotal) {
        List<String> reasons = new ArrayList<>();
        if (signals != null && signals.contains(SIGNAL_INJECTION)) {
            reasons.add(SIGNAL_INJECTION);
        }
        if (budgetTotal != null && budgetTotal.compareTo(amountThreshold) >= 0) {
            reasons.add("AMOUNT_OVER_THRESHOLD");
        }
        if (parsed instanceof RiskSignal r && r.severity() == Severity.HIGH) {
            reasons.add("HIGH_RISK");
        }
        if (parsed instanceof RiskSignal r && "INJECTION".equalsIgnoreCase(r.kind())
                && !reasons.contains(SIGNAL_INJECTION)) {
            reasons.add(SIGNAL_INJECTION);
        }
        if (parsed instanceof Progress p && p.rows().stream().anyMatch(r -> r.actualPct() < r.plannedPct())) {
            reasons.add("PROGRESS_BEHIND_PLAN");
        }
        return reasons;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return m.lines().findFirst().orElse(m);
    }
}
