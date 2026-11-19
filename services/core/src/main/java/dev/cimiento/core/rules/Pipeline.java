package dev.cimiento.core.rules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Rules for moving a tender between pipeline stages. Deterministic and free of I/O: the service
 * gathers the facts, this class decides.
 *
 * <ul>
 *   <li>Only the edges of {@link TenderStage#next()} are allowed.</li>
 *   <li>The decision to bid (IN_PREPARATION), not to bid (DISCARDED) and a lost bid (LOST) must
 *       carry a written reason.</li>
 *   <li>Deciding to bid requires an approved bid analysis.</li>
 *   <li>A bid can only be marked as submitted when no proposal of the tender waits for review.</li>
 *   <li>The last move can be undone, with a reason, only while the tender is still being decided
 *       (under review, decision pending or in preparation), only once in a row, and only by whoever
 *       made it or by management.</li>
 * </ul>
 */
public final class Pipeline {

    public static final Set<TenderStage> NOTE_REQUIRED =
            EnumSet.of(TenderStage.IN_PREPARATION, TenderStage.DISCARDED, TenderStage.LOST);

    /** What the rules need to know about the tender. */
    public record Facts(int pendingReviews, boolean bidAnalysisApproved) {
    }

    /** Stages from which the last move can still be undone: nothing has left the company yet. */
    public static final Set<TenderStage> REVERTIBLE =
            EnumSet.of(TenderStage.UNDER_REVIEW, TenderStage.DECISION_PENDING, TenderStage.IN_PREPARATION);

    /** The latest move of a tender. */
    public record LastMove(TenderStage from, TenderStage to, String person, boolean revert) {
    }

    private Pipeline() {
    }

    /** The stage the last move would go back to, whoever asks; null when it cannot be undone at all. */
    public static TenderStage revertTarget(TenderStage current, LastMove last) {
        if (last == null || last.revert() || last.to() != current || !REVERTIBLE.contains(current)) {
            return null;
        }
        return last.from();
    }

    /** Every reason the last move cannot be undone by this person; empty when it can. */
    public static List<String> checkRevert(TenderStage current, LastMove last, String actorPerson,
                                           boolean management, String note) {
        List<String> errors = new ArrayList<>();
        if (last == null) {
            errors.add("the tender has not moved yet");
            return errors;
        }
        if (last.revert()) {
            errors.add("the last move was already undone");
        } else if (last.to() != current) {
            errors.add("the tender is no longer where the last move left it");
        } else if (!REVERTIBLE.contains(current)) {
            errors.add("a tender in " + current + " can no longer be taken back");
        }
        if (note == null || note.isBlank()) {
            errors.add("undoing a move needs a written reason");
        }
        if (!management && (actorPerson == null || !actorPerson.equals(last.person()))) {
            errors.add("only the person who made the move, or management, can undo it");
        }
        return errors;
    }

    /** Every reason the move is not allowed; empty when it is. */
    public static List<String> check(TenderStage from, TenderStage to, String note, Facts facts) {
        List<String> errors = new ArrayList<>();
        if (!from.next().contains(to)) {
            errors.add("cannot move from " + from + " to " + to);
            return errors;
        }
        if (NOTE_REQUIRED.contains(to) && (note == null || note.isBlank())) {
            errors.add("moving to " + to + " needs a written reason");
        }
        if (to == TenderStage.IN_PREPARATION && !facts.bidAnalysisApproved()) {
            errors.add("deciding to bid needs an approved bid analysis");
        }
        if (to == TenderStage.SUBMITTED && facts.pendingReviews() > 0) {
            errors.add(facts.pendingReviews() + " proposal(s) still wait for review");
        }
        return errors;
    }
}
