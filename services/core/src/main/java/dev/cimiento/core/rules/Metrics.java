package dev.cimiento.core.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Management figures computed from the pipeline, the proposals and the reviewer decisions. Pure:
 * the service gathers the facts, this class counts. Every figure has a plain definition here.
 */
public final class Metrics {

    /** A tender as the metrics need it. {@code budgetTotal} is null when no live budget exists. */
    public record TenderFacts(String code, TenderStage stage, Instant createdAt, LocalDate bidDeadline,
                              BigDecimal budgetTotal) {
    }

    /** One stage move of one tender. */
    public record Move(String tenderCode, TenderStage from, TenderStage to, Instant at) {
    }

    /** One proposal: type, status and, when decided by a person, the outcome. */
    public record ProposalFacts(String type, String status, String route, Instant createdAt, String outcome) {
    }

    public record Deadline(String code, LocalDate date, long daysLeft) {
    }

    public record Result(Map<TenderStage, Integer> tendersByStage, int open, BigDecimal winRatePct,
                         BigDecimal openBudget, BigDecimal awardedBudget, Map<TenderStage, BigDecimal> meanMinutesInStage,
                         Map<String, Integer> pendingByType, BigDecimal oldestPendingHours,
                         Map<String, Integer> decisionsByOutcome, int executedAutomatically, int executedByPerson,
                         int rulesFailed, List<Deadline> upcomingDeadlines) {
    }

    /** Stages where a tender still needs work. */
    public static final List<TenderStage> OPEN = List.of(TenderStage.TO_REVIEW, TenderStage.UNDER_REVIEW,
            TenderStage.DECISION_PENDING, TenderStage.IN_PREPARATION, TenderStage.SUBMITTED);

    private Metrics() {
    }

    /**
     * <ul>
     *   <li>winRatePct: awarded / (awarded + lost), null before any result;</li>
     *   <li>openBudget / awardedBudget: sum of the latest live budget of open / awarded tenders;</li>
     *   <li>meanMinutesInStage: for every move, the time since the tender entered the stage it leaves
     *       (its creation for TO_REVIEW), averaged per stage;</li>
     *   <li>oldestPendingHours: age of the oldest proposal waiting for review;</li>
     *   <li>upcomingDeadlines: open tenders not yet submitted whose deadline is within 30 days (or past).</li>
     * </ul>
     */
    public static Result compute(List<TenderFacts> tenders, List<Move> moves, List<ProposalFacts> proposals,
                                 Instant now, LocalDate today) {
        Map<TenderStage, Integer> byStage = new EnumMap<>(TenderStage.class);
        for (TenderStage s : TenderStage.values()) {
            byStage.put(s, 0);
        }
        tenders.forEach(t -> byStage.merge(t.stage(), 1, Integer::sum));
        int open = OPEN.stream().mapToInt(byStage::get).sum();
        int won = byStage.get(TenderStage.AWARDED);
        int lost = byStage.get(TenderStage.LOST);
        BigDecimal winRate = won + lost == 0 ? null
                : BigDecimal.valueOf(100L * won).divide(BigDecimal.valueOf(won + lost), 1, RoundingMode.HALF_UP);
        BigDecimal openBudget = sum(tenders, t -> OPEN.contains(t.stage()));
        BigDecimal awardedBudget = sum(tenders, t -> t.stage() == TenderStage.AWARDED);

        Map<String, Instant> entered = new LinkedHashMap<>();
        tenders.forEach(t -> entered.put(t.code(), t.createdAt()));
        Map<TenderStage, List<Long>> stays = new EnumMap<>(TenderStage.class);
        moves.stream().sorted(Comparator.comparing(Move::at)).forEach(m -> {
            Instant since = entered.get(m.tenderCode());
            if (since != null) {
                stays.computeIfAbsent(m.from(), k -> new ArrayList<>()).add(Duration.between(since, m.at()).toSeconds());
            }
            entered.put(m.tenderCode(), m.at());
        });
        Map<TenderStage, BigDecimal> meanMinutes = new EnumMap<>(TenderStage.class);
        stays.forEach((stage, seconds) -> meanMinutes.put(stage, BigDecimal.valueOf(seconds.stream()
                .mapToLong(Long::longValue).sum()).divide(BigDecimal.valueOf(60L * seconds.size()), 1,
                RoundingMode.HALF_UP)));

        Map<String, Integer> pendingByType = new TreeMap<>();
        Instant oldest = null;
        Map<String, Integer> outcomes = new TreeMap<>();
        int auto = 0;
        int person = 0;
        int failed = 0;
        for (ProposalFacts p : proposals) {
            switch (p.status()) {
                case "PENDING_REVIEW" -> {
                    pendingByType.merge(p.type(), 1, Integer::sum);
                    oldest = oldest == null || p.createdAt().isBefore(oldest) ? p.createdAt() : oldest;
                }
                case "EXECUTED" -> {
                    if (p.outcome() == null) {
                        auto++;
                    } else {
                        person++;
                    }
                }
                case "RULES_FAILED" -> failed++;
                default -> {
                }
            }
            if (p.outcome() != null) {
                outcomes.merge(p.outcome(), 1, Integer::sum);
            }
        }
        BigDecimal oldestHours = oldest == null ? null
                : BigDecimal.valueOf(Duration.between(oldest, now).toMinutes()).divide(BigDecimal.valueOf(60), 1,
                        RoundingMode.HALF_UP);

        List<Deadline> upcoming = tenders.stream()
                .filter(t -> t.bidDeadline() != null && OPEN.contains(t.stage()) && t.stage() != TenderStage.SUBMITTED)
                .map(t -> new Deadline(t.code(), t.bidDeadline(), ChronoUnit.DAYS.between(today, t.bidDeadline())))
                .filter(d -> d.daysLeft() <= 30)
                .sorted(Comparator.comparing(Deadline::date).thenComparing(Deadline::code))
                .toList();
        return new Result(byStage, open, winRate, openBudget, awardedBudget, meanMinutes, pendingByType, oldestHours,
                outcomes, auto, person, failed, upcoming);
    }

    private static BigDecimal sum(List<TenderFacts> tenders, java.util.function.Predicate<TenderFacts> filter) {
        return tenders.stream().filter(filter).map(TenderFacts::budgetTotal).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
