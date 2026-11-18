package dev.cimiento.core.rules;

/**
 * Proposal kinds and their default route (the routing table). Only records without external
 * effect may run automatically; everything else goes to a person.
 */
public enum ProposalType {
    TAKEOFF(Route.HUMAN),
    BUDGET(Route.HUMAN),
    SCHEDULE(Route.HUMAN),
    BID_ANALYSIS(Route.HUMAN),
    DOCUMENT_DRAFT(Route.HUMAN),
    REPORT(Route.HUMAN),
    RISK_SIGNAL(Route.AUTO),
    PROGRESS_UPDATE(Route.AUTO);

    private final Route defaultRoute;

    ProposalType(Route defaultRoute) {
        this.defaultRoute = defaultRoute;
    }

    public Route defaultRoute() {
        return defaultRoute;
    }

    public enum Route { AUTO, HUMAN }
}
