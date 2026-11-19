package dev.cimiento.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.cimiento.core.domain.CertificationRecord;
import dev.cimiento.core.domain.Proposal;
import dev.cimiento.core.domain.ProposalStatus;
import dev.cimiento.core.domain.RateEntity;
import dev.cimiento.core.domain.Tender;
import dev.cimiento.core.repository.CertificationRepository;
import dev.cimiento.core.repository.RateRepository;
import dev.cimiento.core.repository.TenderRepository;
import dev.cimiento.core.rules.Authority;
import dev.cimiento.core.rules.Certification;
import dev.cimiento.core.rules.Payloads;
import dev.cimiento.core.rules.ProposalType;
import dev.cimiento.core.security.Actor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly certifications. The preview is a read of what the rules accept; issuing recalculates
 * from the approved budget and progress (the client never sends an amount), stores an immutable
 * snapshot and audits it.
 */
@Service
public class CertificationService {

    public record Preview(Long tenderId, String tenderCode, boolean budgetApproved, boolean progressApproved,
                          Certification.Result result) {
    }

    public record IssuedView(Long id, int number, String month, int cutWeek, BigDecimal budget, BigDecimal toDate,
                             BigDecimal previous, BigDecimal period, BigDecimal retention, BigDecimal net,
                             BigDecimal vat, BigDecimal payable, String issuedBy, Instant issuedAt, JsonNode lines) {
    }

    private final TenderRepository tenders;
    private final CertificationRepository certifications;
    private final RateRepository rates;
    private final PortfolioService portfolio;
    private final AuditService audit;
    private final CanonicalJson json;
    private final Certification.Params params;

    public CertificationService(TenderRepository tenders, CertificationRepository certifications,
                                RateRepository rates, PortfolioService portfolio, AuditService audit,
                                CanonicalJson json,
                                @Value("${cimiento.certification.retention-pct:5}") BigDecimal retentionPct,
                                @Value("${cimiento.certification.vat-pct:21}") BigDecimal vatPct) {
        this.tenders = tenders;
        this.certifications = certifications;
        this.rates = rates;
        this.portfolio = portfolio;
        this.audit = audit;
        this.json = json;
        this.params = new Certification.Params(retentionPct, vatPct);
    }

    @Transactional(readOnly = true)
    public Preview preview(Long tenderId, String month) {
        Tender t = tender(tenderId);
        return evaluate(t, month);
    }

    @Transactional(readOnly = true)
    public List<IssuedView> list(Long tenderId) {
        tender(tenderId);
        return certifications.findByTenderIdOrderByNumber(tenderId).stream().map(this::view).toList();
    }

    @Transactional
    public IssuedView issue(Actor actor, Long tenderId, String month) {
        if (actor.person() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "sign in as a person to issue a certification");
        }
        List<Authority.Broken> broken = Authority.checkCertification(actor.personRole());
        if (!broken.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "certification not allowed for " + actor.person(), broken);
        }
        // the tender row is locked so two people cannot issue the same month at once
        Tender t = tenders.lockById(tenderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " not found"));
        Certification.Result r = evaluate(t, month).result();
        if (!r.canIssue()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "certification not allowed", r.errors());
        }
        int number = certifications.findByTenderIdOrderByNumber(tenderId).size() + 1;
        CertificationRecord saved = certifications.save(new CertificationRecord(tenderId, number,
                r.month().toString(), r.cutWeek(), json.write(r.lines()), r.budgetTotal(), r.toDate(), r.previous(),
                r.period(), r.retention(), r.net(), r.vat(), r.payable(), actor.person()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tender", t.getCode());
        details.put("number", number);
        details.put("month", r.month().toString());
        details.put("cutWeek", r.cutWeek());
        details.put("period", r.period().toPlainString());
        details.put("payable", r.payable().toPlainString());
        audit.append(actor, "CERTIFICATION_ISSUED", "certification", saved.getId(), details,
                t.getProject().getCode());
        return view(saved);
    }

    private Tender tender(Long id) {
        return tenders.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + id + " not found"));
    }

    private Preview evaluate(Tender t, String monthText) {
        Proposal budget = portfolio.latestLive(t.getId(), ProposalType.BUDGET)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + t.getId() + " has no budget"));
        Proposal progress = portfolio.latestLive(t.getId(), ProposalType.PROGRESS_UPDATE).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "tender " + t.getId() + " has no site progress"));
        List<CertificationRecord> issuedRows = certifications.findByTenderIdOrderByNumber(t.getId());
        try {
            Map<String, RateEntity> table = rates.findAll().stream()
                    .collect(Collectors.toMap(RateEntity::getCode, Function.identity()));
            var budgetPayload = json.mapper().treeToValue(portfolio.contentOf(budget), Payloads.Budget.class);
            List<Certification.Line> lines = new ArrayList<>();
            for (Payloads.BudgetLine l : budgetPayload.lines()) {
                RateEntity rate = table.get(l.code());
                if (rate == null) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown work item " + l.code());
                }
                lines.add(new Certification.Line(l.code(), rate.toRate().activity(), rate.getDescription(),
                        l.quantity().multiply(rate.toRate().unitPrice()).setScale(2, RoundingMode.HALF_UP)));
            }
            var progressPayload = json.mapper().treeToValue(portfolio.contentOf(progress), Payloads.Progress.class);
            Map<Integer, Map<String, Integer>> weeks = new HashMap<>();
            for (Payloads.ProgressWeek w : progressPayload.historyOrEmpty()) {
                weeks.put(w.week(), percents(w.rows()));
            }
            weeks.put(progressPayload.week(), percents(progressPayload.rows()));
            List<Certification.Issued> issued = new ArrayList<>();
            for (CertificationRecord c : issuedRows) {
                Map<String, BigDecimal> toDate = new HashMap<>();
                for (JsonNode n : json.read(c.getLines())) {
                    toDate.put(n.get("code").asText(), n.get("toDate").decimalValue());
                }
                issued.add(new Certification.Issued(YearMonth.parse(c.getMonth()), c.getNumber(), toDate));
            }
            YearMonth month = monthText == null || monthText.isBlank() ? expected(t, issued) : parse(monthText);
            var input = new Certification.Input(month, t.getPlannedStart(), lines, weeks, issued,
                    budget.getStatus() == ProposalStatus.EXECUTED, progress.getStatus() == ProposalStatus.EXECUTED,
                    params);
            return new Preview(t.getId(), t.getCode(), input.budgetApproved(), input.progressApproved(),
                    Certification.evaluate(input));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "certification cannot be computed: " + e.getMessage());
        }
    }

    private static YearMonth expected(Tender t, List<Certification.Issued> issued) {
        return issued.stream().map(Certification.Issued::month).max(Comparator.naturalOrder())
                .map(m -> m.plusMonths(1)).orElse(Certification.firstMonth(t.getPlannedStart()));
    }

    private static YearMonth parse(String text) {
        try {
            return YearMonth.parse(text);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "month must look like 2027-03");
        }
    }

    private static Map<String, Integer> percents(List<Payloads.ProgressRow> rows) {
        Map<String, Integer> m = new HashMap<>();
        rows.forEach(r -> m.put(r.activity(), r.actualPct()));
        return m;
    }

    private IssuedView view(CertificationRecord c) {
        JsonNode lines = json.read(c.getLines());
        return new IssuedView(c.getId(), c.getNumber(), c.getMonth(), c.getCutWeek(), c.getBudget(), c.getToDate(),
                c.getPrevious(), c.getPeriod(), c.getRetention(), c.getNet(), c.getVat(), c.getPayable(),
                c.getIssuedBy(), c.getIssuedAt(), lines instanceof ArrayNode ? lines : null);
    }
}
