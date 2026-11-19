package dev.cimiento.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cimiento.core.domain.AuditEntry;
import dev.cimiento.core.domain.Project;
import dev.cimiento.core.domain.Proposal;
import dev.cimiento.core.domain.ProposalStatus;
import dev.cimiento.core.domain.Tender;
import dev.cimiento.core.security.ApiKeyFilter;
import dev.cimiento.core.security.Role;
import dev.cimiento.core.service.AuditService;
import dev.cimiento.core.service.CanonicalJson;
import dev.cimiento.core.service.ExportService;
import dev.cimiento.core.service.PortfolioService;
import dev.cimiento.core.service.DashboardService;
import dev.cimiento.core.service.SiteService;
import dev.cimiento.core.service.ProjectService;
import dev.cimiento.core.service.ProposalService;
import dev.cimiento.core.service.QueryService;
import dev.cimiento.core.security.Actor;
import dev.cimiento.core.service.CertificationService;
import dev.cimiento.core.web.ApiDtos.AuditView;
import dev.cimiento.core.web.ApiDtos.DecisionView;
import dev.cimiento.core.web.ApiDtos.CreateProject;
import dev.cimiento.core.web.ApiDtos.CreateTender;
import dev.cimiento.core.web.ApiDtos.DecisionRequest;
import dev.cimiento.core.web.ApiDtos.ProgressView;
import dev.cimiento.core.web.ApiDtos.ProjectView;
import dev.cimiento.core.web.ApiDtos.ProposalView;
import dev.cimiento.core.web.ApiDtos.RateView;
import dev.cimiento.core.web.ApiDtos.ScheduleView;
import dev.cimiento.core.web.ApiDtos.StageChangeView;
import dev.cimiento.core.web.ApiDtos.StageRequest;
import dev.cimiento.core.web.ApiDtos.SubmitProposal;
import dev.cimiento.core.web.ApiDtos.TenderView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API. Thin: validation and mapping here, logic in the services. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ProjectService projects;
    private final ProposalService proposals;
    private final QueryService queries;
    private final AuditService audit;
    private final CanonicalJson json;
    private final PortfolioService portfolio;
    private final ExportService exports;
    private final SiteService site;
    private final DashboardService dashboard;
    private final CertificationService certifications;

    public ApiController(ProjectService projects, ProposalService proposals, QueryService queries,
                         AuditService audit, CanonicalJson json, PortfolioService portfolio, ExportService exports,
                         SiteService site, DashboardService dashboard,
                         CertificationService certifications) {
        this.certifications = certifications;
        this.site = site;
        this.dashboard = dashboard;
        this.portfolio = portfolio;
        this.exports = exports;
        this.projects = projects;
        this.proposals = proposals;
        this.queries = queries;
        this.audit = audit;
        this.json = json;
    }

    @PostMapping("/projects")
    public ResponseEntity<ProjectView> createProject(@RequestAttribute(ApiKeyFilter.ROLE_ATTRIBUTE) Role role,
                                                     @Valid @RequestBody CreateProject body) {
        var created = projects.createProject(role, body.code(), body.name());
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(view(created.value()));
    }

    @GetMapping("/projects")
    public List<ProjectView> listProjects() {
        return projects.listProjects().stream().map(ApiController::view).toList();
    }

    @GetMapping("/projects/{id}/tenders")
    public List<TenderView> listTenders(@PathVariable Long id) {
        return projects.listTenders(id).stream().map(ApiController::view).toList();
    }

    @PostMapping("/tenders")
    public ResponseEntity<TenderView> createTender(@RequestAttribute(ApiKeyFilter.ROLE_ATTRIBUTE) Role role,
                                                   @Valid @RequestBody CreateTender body) {
        var created = projects.createTender(role, body.projectCode(), body.code(), body.title(),
                body.contractTermMonths(), body.plannedStart());
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(view(created.value()));
    }

    @GetMapping("/tenders/{id}")
    public TenderView tender(@PathVariable Long id) {
        return view(projects.tender(id));
    }

    @GetMapping("/portfolio")
    public List<PortfolioService.Item> portfolio() {
        return portfolio.portfolio();
    }

    @GetMapping("/portfolio/{tenderId}")
    public PortfolioService.Item portfolioItem(@PathVariable Long tenderId) {
        return portfolio.item(tenderId);
    }

    @GetMapping("/tenders/{id}/site")
    public SiteService.SiteView site(@PathVariable Long id) {
        return site.site(id);
    }

    @GetMapping("/dashboard")
    public dev.cimiento.core.rules.Metrics.Result dashboard() {
        return dashboard.dashboard();
    }

    @PostMapping("/tenders/{id}/stage")
    public PortfolioService.Item moveStage(@RequestAttribute(ApiKeyFilter.ACTOR_ATTRIBUTE) Actor actor,
                                           @PathVariable Long id, @Valid @RequestBody StageRequest body) {
        return portfolio.move(actor, id, body.stage(), body.note());
    }

    @GetMapping("/tenders/{id}/certification")
    public CertificationService.Preview certificationPreview(@PathVariable Long id,
                                                             @RequestParam(required = false) String month) {
        return certifications.preview(id, month);
    }

    @GetMapping("/tenders/{id}/certifications")
    public List<CertificationService.IssuedView> certificationList(@PathVariable Long id) {
        return certifications.list(id);
    }

    @PostMapping("/tenders/{id}/certifications")
    public ResponseEntity<CertificationService.IssuedView> issueCertification(
            @RequestAttribute(ApiKeyFilter.ACTOR_ATTRIBUTE) Actor actor, @PathVariable Long id,
            @Valid @RequestBody ApiDtos.CertificationRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(certifications.issue(actor, id, body.month()));
    }

    @GetMapping("/tenders/{id}/stages")
    public List<StageChangeView> stageHistory(@PathVariable Long id) {
        return portfolio.history(id).stream().map(c -> new StageChangeView(c.getId(), c.getFromStage(),
                c.getToStage(), c.getNote(), c.getActorRole(), c.getPerson(), c.getCreatedAt())).toList();
    }

    @GetMapping("/proposals/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        var file = exports.export(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @GetMapping("/tenders/{id}/schedule")
    public ScheduleView schedule(@PathVariable Long id) {
        return queries.latestSchedule(id);
    }

    @GetMapping("/tenders/{id}/progress")
    public List<ProgressView> progress(@PathVariable Long id) {
        return queries.progress(id);
    }

    @PostMapping("/proposals")
    public ResponseEntity<ProposalView> submit(@RequestAttribute(ApiKeyFilter.ROLE_ATTRIBUTE) Role role,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody SubmitProposal body) {
        var submitted = proposals.submit(role, key, body.tenderId(), body.type(), body.payload(), body.citations(),
                body.signals());
        return ResponseEntity.status(submitted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(submitted.replayed()))
                .body(view(submitted.proposal()));
    }

    @GetMapping("/proposals")
    public List<ProposalView> listProposals(@RequestParam(required = false) ProposalStatus status,
                                            @RequestParam(required = false) Long tenderId) {
        return proposals.list(status, tenderId).stream().map(this::view).toList();
    }

    @GetMapping("/proposals/{id}")
    public ProposalView proposal(@PathVariable Long id) {
        return view(proposals.proposal(id));
    }

    @PostMapping("/proposals/{id}/decision")
    public ProposalView decide(@RequestAttribute(ApiKeyFilter.ACTOR_ATTRIBUTE) Actor actor, @PathVariable Long id,
                               @Valid @RequestBody DecisionRequest body) {
        return view(proposals.decide(actor, id, body.outcome(), body.comment(), body.correctedPayload()));
    }

    @GetMapping("/audit")
    public List<AuditView> audit(@RequestParam(defaultValue = "0") Long afterSeq) {
        return queries.audit(afterSeq).stream().map(this::view).toList();
    }

    @GetMapping("/audit/verify")
    public AuditService.Verification verifyAudit() {
        return audit.verify();
    }

    @GetMapping("/rates")
    public List<RateView> rates() {
        return queries.rates();
    }

    private static ProjectView view(Project p) {
        return new ProjectView(p.getId(), p.getCode(), p.getName());
    }

    private static TenderView view(Tender t) {
        return new TenderView(t.getId(), t.getProject().getCode(), t.getCode(), t.getTitle(),
                t.getContractTermMonths(), t.getPlannedStart(), t.getStage());
    }

    private ProposalView view(Proposal p) {
        return new ProposalView(p.getId(), p.getTender().getId(), p.getTender().getCode(), p.getType(),
                p.getStatus().name(), p.getRoute(), json.read(p.getPayload()), json.read(p.getExecutedPayload()),
                json.read(p.getCitations()), json.read(p.getSignals()), json.read(p.getRuleResults()),
                json.read(p.getRoutingReasons()), p.getCreatedBy(), p.getCreatedAt(), p.getDecidedAt(),
                json.read(p.getCorrectedPayload()), p.getCorrectedBy(), proposals.decisions(p.getId()).stream()
                        .map(d -> new DecisionView(d.getOutcome(), d.getPerson(), d.getCreatedAt())).toList());
    }

    private AuditView view(AuditEntry e) {
        JsonNode details = json.read(e.getDetails());
        return new AuditView(e.getSeq(), e.getCreatedAt(), e.getActorRole(), e.getActorPerson(), e.getAction(), e.getEntityType(),
                e.getEntityId(), details, e.getPrevHash(), e.getHash());
    }
}
