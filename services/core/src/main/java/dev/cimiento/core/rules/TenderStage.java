package dev.cimiento.core.rules;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a tender stands in the bid pipeline. A tender only moves forward along these edges, or is
 * discarded; AWARDED, LOST and DISCARDED are final.
 */
public enum TenderStage {
    TO_REVIEW, UNDER_REVIEW, DECISION_PENDING, IN_PREPARATION, SUBMITTED, AWARDED, LOST, DISCARDED;

    public Set<TenderStage> next() {
        return switch (this) {
            case TO_REVIEW -> EnumSet.of(UNDER_REVIEW, DISCARDED);
            case UNDER_REVIEW -> EnumSet.of(DECISION_PENDING, DISCARDED);
            case DECISION_PENDING -> EnumSet.of(IN_PREPARATION, DISCARDED);
            case IN_PREPARATION -> EnumSet.of(SUBMITTED, DISCARDED);
            case SUBMITTED -> EnumSet.of(AWARDED, LOST);
            case AWARDED, LOST, DISCARDED -> EnumSet.noneOf(TenderStage.class);
        };
    }

    public boolean isFinal() {
        return next().isEmpty();
    }
}
