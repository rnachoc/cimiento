package dev.cimiento.core.rules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Who may decide what. Pure rules, no Spring: each check returns the broken rules (empty when the
 * person may act). Management may decide everything; amounts over the review threshold and the
 * bid / no-bid moves are reserved to management; a person who corrected a proposal cannot also
 * give it the second approval.
 */
public final class Authority {

    public static final String ROLE_SCOPE = "ROLE_SCOPE";
    public static final String AMOUNT_RESERVED = "AMOUNT_RESERVED_TO_MANAGEMENT";
    public static final String SEGREGATION = "SEGREGATION_OF_DUTIES";
    public static final String STAGE_RESERVED = "STAGE_RESERVED_TO_MANAGEMENT";

    private static final Set<ProposalType> ESTIMATING_TYPES = EnumSet.of(ProposalType.TAKEOFF, ProposalType.BUDGET,
            ProposalType.BID_ANALYSIS, ProposalType.DOCUMENT_DRAFT);
    private static final Set<ProposalType> SITE_TYPES = EnumSet.of(ProposalType.SCHEDULE, ProposalType.REPORT,
            ProposalType.PROGRESS_UPDATE, ProposalType.RISK_SIGNAL);
    private static final Set<TenderStage> MANAGEMENT_STAGES = EnumSet.of(TenderStage.IN_PREPARATION,
            TenderStage.DISCARDED);

    public record Broken(String rule, String message) {
    }

    private Authority() {
    }

    /** Proposal types a role may decide on its own. */
    public static Set<ProposalType> scope(PersonRole role) {
        return switch (role) {
            case ESTIMATING -> ESTIMATING_TYPES;
            case SITE_MANAGER -> SITE_TYPES;
            case MANAGEMENT -> EnumSet.allOf(ProposalType.class);
        };
    }

    /**
     * @param reasons     routing reasons of the proposal (AMOUNT_OVER_THRESHOLD reserves it to management)
     * @param correctedBy username of the person who corrected it, or null for a first decision
     */
    public static List<Broken> checkDecision(PersonRole role, String username, ProposalType type,
                                             List<String> reasons, String correctedBy) {
        List<Broken> broken = new ArrayList<>();
        if (!scope(role).contains(type)) {
            broken.add(new Broken(ROLE_SCOPE, role + " may not decide " + type));
        }
        if (reasons != null && reasons.contains("AMOUNT_OVER_THRESHOLD") && role != PersonRole.MANAGEMENT) {
            broken.add(new Broken(AMOUNT_RESERVED, "amounts over the review threshold are decided by management"));
        }
        if (correctedBy != null && correctedBy.equals(username)) {
            broken.add(new Broken(SEGREGATION, "the person who corrected a proposal cannot approve it"));
        }
        return broken;
    }

    /** Issuing a certification is a site or management act; estimating does not issue them. */
    public static List<Broken> checkCertification(PersonRole role) {
        return role == PersonRole.ESTIMATING
                ? List.of(new Broken(ROLE_SCOPE, "ESTIMATING does not issue certifications")) : List.of();
    }

    /** The work calendar shapes the schedule: site managers and management set it, estimating does not. */
    public static List<Broken> checkCalendar(PersonRole role) {
        return role == PersonRole.ESTIMATING
                ? List.of(new Broken(ROLE_SCOPE, "ESTIMATING does not change the work calendar")) : List.of();
    }

    /** Bringing in a new tender is estimating's job (and management's); site managers do not. */
    public static List<Broken> checkIntake(PersonRole role) {
        return role == PersonRole.SITE_MANAGER
                ? List.of(new Broken(ROLE_SCOPE, "SITE_MANAGER does not bring in new tenders")) : List.of();
    }

    public static List<Broken> checkStage(PersonRole role, TenderStage to) {
        if (MANAGEMENT_STAGES.contains(to) && role != PersonRole.MANAGEMENT) {
            return List.of(new Broken(STAGE_RESERVED, "moving a tender to " + to + " is decided by management"));
        }
        if (role == PersonRole.SITE_MANAGER) {
            return List.of(new Broken(ROLE_SCOPE, "SITE_MANAGER does not move tenders through the bid pipeline"));
        }
        return List.of();
    }
}
