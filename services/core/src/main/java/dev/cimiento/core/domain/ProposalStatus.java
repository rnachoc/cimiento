package dev.cimiento.core.domain;

/** Lifecycle: rules fail, or the proposal waits for a person, or it is executed or rejected. */
public enum ProposalStatus {
    RULES_FAILED,
    PENDING_REVIEW,
    EXECUTED,
    REJECTED
}
