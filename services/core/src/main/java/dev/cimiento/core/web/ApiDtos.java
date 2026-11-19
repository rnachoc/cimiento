package dev.cimiento.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cimiento.core.domain.Decision;
import dev.cimiento.core.rules.Payloads.Citation;
import dev.cimiento.core.rules.ProposalType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Request and response bodies of the REST API. */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record CreateProject(@NotBlank String code, @NotBlank String name) {
    }

    public record ProjectView(Long id, String code, String name) {
    }

    public record CreateTender(@NotBlank String projectCode, @NotBlank String code, @NotBlank String title,
                               @Min(1) Integer contractTermMonths, @NotNull LocalDate plannedStart) {
    }

    public record TenderView(Long id, String projectCode, String code, String title, Integer contractTermMonths,
                             LocalDate plannedStart, dev.cimiento.core.rules.TenderStage stage) {
    }

    public record StageRequest(@NotNull dev.cimiento.core.rules.TenderStage stage,
                               @jakarta.validation.constraints.Size(max = 500) String note) {
    }

    public record RevertRequest(@jakarta.validation.constraints.Size(max = 500) String note) {
    }

    public record StageChangeView(Long id, dev.cimiento.core.rules.TenderStage from,
                                  dev.cimiento.core.rules.TenderStage to, String note, String actorRole,
                                  String person, java.time.Instant at, boolean revert) {
    }

    public record SubmitProposal(@NotNull Long tenderId, @NotNull ProposalType type, @NotNull JsonNode payload,
                                 List<Citation> citations, List<String> signals) {
    }

    public record ProposalView(Long id, Long tenderId, String tenderCode, ProposalType type, String status,
                               ProposalType.Route route, JsonNode payload, JsonNode executedPayload,
                               JsonNode citations, JsonNode signals, JsonNode ruleResults, JsonNode routingReasons,
                               String createdBy, Instant createdAt, Instant decidedAt, JsonNode correctedPayload,
                               String correctedBy, List<DecisionView> decisions) {
    }

    public record DecisionView(Decision.Outcome outcome, String person, Instant at) {
    }

    public record CertificationRequest(@NotNull @jakarta.validation.constraints.Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String month) {
    }

    public record SessionRequest(@NotNull @jakarta.validation.constraints.Size(max = 40) String username,
                                 @NotNull @jakarta.validation.constraints.Size(max = 200) String token) {
    }

    public record PersonView(String username, String displayName, dev.cimiento.core.rules.PersonRole role,
                             java.util.Set<ProposalType> scope) {
    }

    public record DecisionRequest(@NotNull Decision.Outcome outcome, String comment, JsonNode correctedPayload) {
    }

    public record ActivityView(String code, String name, int duration, int es, int ef, int ls, int lf,
                               int totalFloat, boolean critical) {
    }

    public record LinkView(String from, String to, int lag) {
    }

    public record ScheduleView(Long id, Long tenderId, Long proposalId, int durationDays, LocalDate startDate,
                               LocalDate endDate, LocalDate contractDeadline, Boolean exceedsTerm,
                               List<String> critical, List<ActivityView> activities, List<LinkView> links) {
    }

    public record ProgressView(int week, String activity, int plannedPct, int actualPct) {
    }

    public record AuditView(Long seq, Instant createdAt, String actorRole, String actorPerson, String action, String entityType,
                            String entityId, JsonNode details, String prevHash, String hash) {
    }

    public record RateView(String code, String description, String activity, String unit, String unitPrice,
                           String ratePerDay) {
    }

    public record ErrorView(String error, String message, Object details) {
    }
}
