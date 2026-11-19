package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuthorityTest {

    private static List<String> rules(List<Authority.Broken> broken) {
        return broken.stream().map(Authority.Broken::rule).toList();
    }

    @Test
    void eachRoleDecidesOnlyItsOwnTypesAndManagementDecidesAll() {
        for (ProposalType t : ProposalType.values()) {
            assertThat(Authority.checkDecision(PersonRole.MANAGEMENT, "g", t, List.of(), null)).isEmpty();
            boolean estimating = Authority.scope(PersonRole.ESTIMATING).contains(t);
            boolean site = Authority.scope(PersonRole.SITE_MANAGER).contains(t);
            // every type belongs to exactly one non-management role
            assertThat(estimating ^ site).as(t.name()).isTrue();
            assertThat(Authority.checkDecision(PersonRole.ESTIMATING, "e", t, List.of(), null).isEmpty())
                    .isEqualTo(estimating);
        }
        assertThat(rules(Authority.checkDecision(PersonRole.ESTIMATING, "e", ProposalType.SCHEDULE, List.of(), null)))
                .containsExactly(Authority.ROLE_SCOPE);
        assertThat(Authority.checkDecision(PersonRole.SITE_MANAGER, "o", ProposalType.PROGRESS_UPDATE, List.of(), null))
                .isEmpty();
    }

    @Test
    void amountsOverTheThresholdAreForManagementOnly() {
        List<String> reasons = List.of("AMOUNT_OVER_THRESHOLD");
        assertThat(rules(Authority.checkDecision(PersonRole.ESTIMATING, "e", ProposalType.BUDGET, reasons, null)))
                .containsExactly(Authority.AMOUNT_RESERVED);
        assertThat(Authority.checkDecision(PersonRole.MANAGEMENT, "g", ProposalType.BUDGET, reasons, null)).isEmpty();
        assertThat(Authority.checkDecision(PersonRole.ESTIMATING, "e", ProposalType.BUDGET, List.of("HIGH_RISK"), null))
                .isEmpty();
    }

    @Test
    void whoCorrectedCannotApprove() {
        assertThat(rules(Authority.checkDecision(PersonRole.MANAGEMENT, "g", ProposalType.BUDGET, List.of(), "g")))
                .containsExactly(Authority.SEGREGATION);
        assertThat(Authority.checkDecision(PersonRole.MANAGEMENT, "g", ProposalType.BUDGET, List.of(), "e")).isEmpty();
        // several broken rules are all reported
        assertThat(rules(Authority.checkDecision(PersonRole.SITE_MANAGER, "o", ProposalType.BUDGET,
                List.of("AMOUNT_OVER_THRESHOLD"), "o")))
                .containsExactly(Authority.ROLE_SCOPE, Authority.AMOUNT_RESERVED, Authority.SEGREGATION);
    }

    @Test
    void bidNoBidMovesAreReservedAndSiteManagersDoNotMoveTenders() {
        assertThat(rules(Authority.checkStage(PersonRole.ESTIMATING, TenderStage.IN_PREPARATION)))
                .containsExactly(Authority.STAGE_RESERVED);
        assertThat(rules(Authority.checkStage(PersonRole.ESTIMATING, TenderStage.DISCARDED)))
                .containsExactly(Authority.STAGE_RESERVED);
        assertThat(Authority.checkStage(PersonRole.ESTIMATING, TenderStage.SUBMITTED)).isEmpty();
        assertThat(rules(Authority.checkStage(PersonRole.SITE_MANAGER, TenderStage.UNDER_REVIEW)))
                .containsExactly(Authority.ROLE_SCOPE);
        for (TenderStage s : TenderStage.values()) {
            assertThat(Authority.checkStage(PersonRole.MANAGEMENT, s)).isEmpty();
        }
    }

    @Test
    void estimatingDoesNotIssueCertifications() {
        assertThat(rules(Authority.checkCertification(PersonRole.ESTIMATING))).containsExactly(Authority.ROLE_SCOPE);
        assertThat(Authority.checkCertification(PersonRole.SITE_MANAGER)).isEmpty();
        assertThat(Authority.checkCertification(PersonRole.MANAGEMENT)).isEmpty();
    }
}
