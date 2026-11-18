package dev.cimiento.core.rules;

import static dev.cimiento.core.rules.TenderStage.AWARDED;
import static dev.cimiento.core.rules.TenderStage.DECISION_PENDING;
import static dev.cimiento.core.rules.TenderStage.DISCARDED;
import static dev.cimiento.core.rules.TenderStage.IN_PREPARATION;
import static dev.cimiento.core.rules.TenderStage.LOST;
import static dev.cimiento.core.rules.TenderStage.SUBMITTED;
import static dev.cimiento.core.rules.TenderStage.TO_REVIEW;
import static dev.cimiento.core.rules.TenderStage.UNDER_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PipelineTest {

    static final Pipeline.Facts READY = new Pipeline.Facts(0, true);

    @Test
    void onlyForwardEdgesOrDiscardAreAllowed() {
        assertThat(Pipeline.check(TO_REVIEW, UNDER_REVIEW, null, READY)).isEmpty();
        assertThat(Pipeline.check(UNDER_REVIEW, DECISION_PENDING, null, READY)).isEmpty();
        assertThat(Pipeline.check(TO_REVIEW, SUBMITTED, null, READY)).containsExactly("cannot move from TO_REVIEW to SUBMITTED");
        assertThat(Pipeline.check(UNDER_REVIEW, TO_REVIEW, null, READY)).hasSize(1);
        assertThat(Pipeline.check(SUBMITTED, DISCARDED, "x", READY)).hasSize(1);
        assertThat(Pipeline.check(SUBMITTED, AWARDED, null, READY)).isEmpty();
    }

    @Test
    void finalStagesHaveNoWayOut() {
        for (TenderStage end : new TenderStage[] {AWARDED, LOST, DISCARDED}) {
            assertThat(end.isFinal()).isTrue();
            for (TenderStage to : TenderStage.values()) {
                assertThat(Pipeline.check(end, to, "reason", READY)).isNotEmpty();
            }
        }
        assertThat(TO_REVIEW.isFinal()).isFalse();
    }

    @Test
    void bidDecisionsNeedAWrittenReason() {
        assertThat(Pipeline.check(DECISION_PENDING, IN_PREPARATION, null, READY))
                .containsExactly("moving to IN_PREPARATION needs a written reason");
        assertThat(Pipeline.check(DECISION_PENDING, IN_PREPARATION, "   ", READY)).hasSize(1);
        assertThat(Pipeline.check(TO_REVIEW, DISCARDED, null, READY)).hasSize(1);
        assertThat(Pipeline.check(SUBMITTED, LOST, null, READY)).hasSize(1);
        assertThat(Pipeline.check(DECISION_PENDING, IN_PREPARATION, "margin and solvency fit", READY)).isEmpty();
    }

    @Test
    void decidingToBidNeedsAnApprovedBidAnalysis() {
        assertThat(Pipeline.check(DECISION_PENDING, IN_PREPARATION, "ok", new Pipeline.Facts(0, false)))
                .containsExactly("deciding to bid needs an approved bid analysis");
        // discarding does not
        assertThat(Pipeline.check(DECISION_PENDING, DISCARDED, "no fit", new Pipeline.Facts(0, false))).isEmpty();
    }

    @Test
    void submittingNeedsEveryProposalDecided() {
        assertThat(Pipeline.check(IN_PREPARATION, SUBMITTED, null, new Pipeline.Facts(2, true)))
                .containsExactly("2 proposal(s) still wait for review");
        assertThat(Pipeline.check(IN_PREPARATION, SUBMITTED, null, READY)).isEmpty();
    }
}
