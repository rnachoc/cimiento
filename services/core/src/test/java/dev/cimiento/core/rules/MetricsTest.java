package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsTest {

    static final Instant T0 = Instant.parse("2027-01-04T08:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2027, 1, 10);

    static Instant h(int hours) {
        return T0.plusSeconds(3600L * hours);
    }

    static final List<Metrics.TenderFacts> TENDERS = List.of(
            new Metrics.TenderFacts("E-1", TenderStage.AWARDED, T0, LocalDate.of(2027, 1, 5), new BigDecimal("100.00")),
            new Metrics.TenderFacts("E-2", TenderStage.LOST, T0, null, new BigDecimal("50.00")),
            new Metrics.TenderFacts("E-3", TenderStage.LOST, T0, null, null),
            new Metrics.TenderFacts("E-4", TenderStage.UNDER_REVIEW, T0, LocalDate.of(2027, 1, 20), new BigDecimal("30.50")),
            new Metrics.TenderFacts("E-5", TenderStage.TO_REVIEW, T0, LocalDate.of(2027, 1, 8), new BigDecimal("10.00")),
            new Metrics.TenderFacts("E-6", TenderStage.SUBMITTED, T0, LocalDate.of(2027, 1, 12), new BigDecimal("1.00")),
            new Metrics.TenderFacts("E-7", TenderStage.DECISION_PENDING, T0, LocalDate.of(2027, 3, 1), null));

    @Test
    void countsStagesWinRateAndBudgets() {
        var r = Metrics.compute(TENDERS, List.of(), List.of(), h(200), TODAY);
        assertThat(r.tendersByStage().get(TenderStage.LOST)).isEqualTo(2);
        assertThat(r.tendersByStage().get(TenderStage.IN_PREPARATION)).isZero();
        assertThat(r.open()).isEqualTo(4);
        assertThat(r.winRatePct()).isEqualByComparingTo("33.3"); // 1 awarded / (1 + 2 lost)
        assertThat(r.openBudget()).isEqualByComparingTo("41.50"); // E-4, E-5, E-6 (E-7 has no budget)
        assertThat(r.awardedBudget()).isEqualByComparingTo("100.00");
    }

    @Test
    void noResultYetMeansNoWinRate() {
        var r = Metrics.compute(TENDERS.subList(3, 5), List.of(), List.of(), h(1), TODAY);
        assertThat(r.winRatePct()).isNull();
    }

    @Test
    void upcomingDeadlinesSkipSubmittedClosedAndFarDates() {
        var r = Metrics.compute(TENDERS, List.of(), List.of(), h(1), TODAY);
        // E-5 passed 2 days ago, E-4 in 10 days; E-6 is submitted, E-1 closed, E-7 in 50 days
        assertThat(r.upcomingDeadlines()).extracting(Metrics.Deadline::code).containsExactly("E-5", "E-4");
        assertThat(r.upcomingDeadlines()).extracting(Metrics.Deadline::daysLeft).containsExactly(-2L, 10L);
    }

    @Test
    void meanTimeInStageCountsFromCreationThenFromEachMove() {
        var moves = List.of(
                new Metrics.Move("E-4", TenderStage.TO_REVIEW, TenderStage.UNDER_REVIEW, h(10)),
                new Metrics.Move("E-5", TenderStage.TO_REVIEW, TenderStage.UNDER_REVIEW, h(30)),
                new Metrics.Move("E-4", TenderStage.UNDER_REVIEW, TenderStage.DECISION_PENDING, h(15)));
        var r = Metrics.compute(TENDERS, moves, List.of(), h(40), TODAY);
        assertThat(r.meanMinutesInStage().get(TenderStage.TO_REVIEW)).isEqualByComparingTo("1200.0"); // (10 h + 30 h) / 2
        assertThat(r.meanMinutesInStage().get(TenderStage.UNDER_REVIEW)).isEqualByComparingTo("300.0");
        assertThat(r.meanMinutesInStage()).doesNotContainKey(TenderStage.SUBMITTED);
    }

    @Test
    void reviewLoadAndDecisions() {
        var proposals = List.of(
                new Metrics.ProposalFacts("BUDGET", "PENDING_REVIEW", "HUMAN", h(2), null),
                new Metrics.ProposalFacts("BUDGET", "PENDING_REVIEW", "HUMAN", h(5), null),
                new Metrics.ProposalFacts("SCHEDULE", "PENDING_REVIEW", "HUMAN", h(8), null),
                new Metrics.ProposalFacts("RISK_SIGNAL", "EXECUTED", "AUTO", h(1), null),
                new Metrics.ProposalFacts("SCHEDULE", "EXECUTED", "HUMAN", h(1), "APPROVE"),
                new Metrics.ProposalFacts("BUDGET", "EXECUTED", "HUMAN", h(1), "CORRECT"),
                new Metrics.ProposalFacts("REPORT", "REJECTED", "HUMAN", h(1), "REJECT"),
                new Metrics.ProposalFacts("SCHEDULE", "RULES_FAILED", null, h(1), null));
        var r = Metrics.compute(TENDERS, List.of(), proposals, h(12), TODAY);
        assertThat(r.pendingByType()).containsEntry("BUDGET", 2).containsEntry("SCHEDULE", 1).hasSize(2);
        assertThat(r.oldestPendingHours()).isEqualByComparingTo("10.0");
        assertThat(r.executedAutomatically()).isEqualTo(1);
        assertThat(r.executedByPerson()).isEqualTo(2);
        assertThat(r.rulesFailed()).isEqualTo(1);
        assertThat(r.decisionsByOutcome()).containsEntry("APPROVE", 1).containsEntry("CORRECT", 1)
                .containsEntry("REJECT", 1);
    }
}
