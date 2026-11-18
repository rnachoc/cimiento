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
 * </ul>
 */
public final class Pipeline {

    public static final Set<TenderStage> NOTE_REQUIRED =
            EnumSet.of(TenderStage.IN_PREPARATION, TenderStage.DISCARDED, TenderStage.LOST);

    /** What the rules need to know about the tender. */
    public record Facts(int pendingReviews, boolean bidAnalysisApproved) {
    }

    private Pipeline() {
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
