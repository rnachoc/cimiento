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

    static final Pipeline.LastMove BY_ESTUDIOS = new Pipeline.LastMove(UNDER_REVIEW, DECISION_PENDING, "estudios", false);

    @Test
    void theLastMoveCanBeTakenBackWhileTheTenderIsStillBeingDecided() {
        assertThat(Pipeline.revertTarget(DECISION_PENDING, BY_ESTUDIOS)).isEqualTo(UNDER_REVIEW);
        assertThat(Pipeline.checkRevert(DECISION_PENDING, BY_ESTUDIOS, "estudios", false, "me equivoqué")).isEmpty();
        // under review and in preparation can be taken back too
        var toReview = new Pipeline.LastMove(TO_REVIEW, UNDER_REVIEW, "estudios", false);
        var toPrep = new Pipeline.LastMove(DECISION_PENDING, IN_PREPARATION, "gerencia", false);
        assertThat(Pipeline.revertTarget(UNDER_REVIEW, toReview)).isEqualTo(TO_REVIEW);
        assertThat(Pipeline.revertTarget(IN_PREPARATION, toPrep)).isEqualTo(DECISION_PENDING);
    }

    @Test
    void aSubmittedOrClosedTenderCannotBeTakenBack() {
        for (TenderStage end : new TenderStage[] {SUBMITTED, AWARDED, LOST, DISCARDED}) {
            var last = new Pipeline.LastMove(IN_PREPARATION, end, "gerencia", false);
            assertThat(Pipeline.revertTarget(end, last)).isNull();
            assertThat(Pipeline.checkRevert(end, last, "gerencia", true, "x")).isNotEmpty();
        }
        assertThat(Pipeline.revertTarget(TO_REVIEW, null)).isNull();
    }

    @Test
    void anUndoNeedsAReasonAndOnlyItsAuthorOrManagementMayDoIt() {
        assertThat(Pipeline.checkRevert(DECISION_PENDING, BY_ESTUDIOS, "estudios", false, " "))
                .containsExactly("undoing a move needs a written reason");
        assertThat(Pipeline.checkRevert(DECISION_PENDING, BY_ESTUDIOS, "otra", false, "x"))
                .containsExactly("only the person who made the move, or management, can undo it");
        assertThat(Pipeline.checkRevert(DECISION_PENDING, BY_ESTUDIOS, "gerencia", true, "x")).isEmpty();
        assertThat(Pipeline.checkRevert(DECISION_PENDING, null, "gerencia", true, "x"))
                .containsExactly("the tender has not moved yet");
    }

    @Test
    void anUndoCannotBeUndone() {
        var undo = new Pipeline.LastMove(DECISION_PENDING, UNDER_REVIEW, "estudios", true);
        assertThat(Pipeline.revertTarget(UNDER_REVIEW, undo)).isNull();
        assertThat(Pipeline.checkRevert(UNDER_REVIEW, undo, "estudios", true, "x"))
                .containsExactly("the last move was already undone");
    }
}
