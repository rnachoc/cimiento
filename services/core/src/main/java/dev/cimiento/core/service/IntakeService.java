package dev.cimiento.core.service;

import dev.cimiento.core.domain.Intake;
import dev.cimiento.core.domain.IntakeDocument;
import dev.cimiento.core.repository.IntakeDocumentRepository;
import dev.cimiento.core.repository.IntakeRepository;
import dev.cimiento.core.repository.TenderRepository;
import dev.cimiento.core.rules.Authority;
import dev.cimiento.core.security.Actor;
import dev.cimiento.core.security.Role;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tender intake: a person uploads the tender documents; an agent worker claims the job, reads the
 * documents and submits proposals through the usual API; then it reports back. Nothing here reads
 * the documents: they are stored as received and only their size and SHA-256 are recorded.
 */
@Service
public class IntakeService {

    /** Names the agents read (pliego is required, the others optional). */
    public static final List<String> DOCUMENTS = List.of("pliego", "anexo", "mediciones");
    /** The project every analysed tender belongs to (the agents register tenders there). */
    public static final String PROJECT = "LICITACIONES";
    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final Duration STALE = Duration.ofMinutes(15);
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");

    public record View(Long id, String code, String title, LocalDate plannedStart, Intake.Status status, int documents,
                       long bytes, String uploadedBy, Instant createdAt, Instant startedAt, Instant finishedAt,
                       Long tenderId, Integer proposals, String message) {
    }

    public record DocumentRef(String name, String sha256, long size) {
    }

    public record Job(Long id, String code, String title, LocalDate plannedStart, List<DocumentRef> documents) {
    }

    private final IntakeRepository intakes;
    private final IntakeDocumentRepository documents;
    private final TenderRepository tenders;
    private final ProjectService projects;
    private final AuditService audit;

    public IntakeService(IntakeRepository intakes, IntakeDocumentRepository documents, TenderRepository tenders,
                         ProjectService projects, AuditService audit) {
        this.intakes = intakes;
        this.documents = documents;
        this.tenders = tenders;
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional
    public View receive(Actor actor, String code, String title, LocalDate plannedStart, Map<String, byte[]> files) {
        if (actor.person() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "sign in as a person to upload a tender");
        }
        List<Authority.Broken> broken = Authority.checkIntake(actor.personRole());
        if (!broken.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "upload not allowed for " + actor.person(), broken);
        }
        String c = code == null ? "" : code.strip();
        String t = title == null ? "" : title.strip();
        List<String> errors = new ArrayList<>();
        if (!CODE.matcher(c).matches()) {
            errors.add("the code must be 1 to 40 letters, digits, dots, dashes or underscores");
        }
        if (t.isEmpty() || t.length() > 200) {
            errors.add("the title must have 1 to 200 characters");
        }
        if (plannedStart == null || plannedStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            errors.add("the planned start must be a Monday");
        }
        Map<String, byte[]> kept = new LinkedHashMap<>();
        for (String name : DOCUMENTS) {
            byte[] f = files.get(name);
            if (f == null || f.length == 0) {
                continue;
            }
            if (f.length > MAX_BYTES) {
                errors.add(name + " is larger than 20 MB");
            } else if (!isPdf(f)) {
                errors.add(name + " is not a PDF");
            } else {
                kept.put(name, f);
            }
        }
        for (String name : files.keySet()) {
            if (!DOCUMENTS.contains(name)) {
                errors.add("unknown document " + name + " (expected pliego, anexo or mediciones)");
            }
        }
        if (!kept.containsKey("pliego") && errors.stream().noneMatch(e -> e.startsWith("pliego"))) {
            errors.add("the pliego (tender specification) is required");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "intake not valid", errors);
        }
        if (intakes.existsByCode(c) || tenders.findByCode(c).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "a tender with code " + c + " already exists");
        }
        projects.createProject(Role.REVIEWER, PROJECT, "Licitaciones");
        long bytes = kept.values().stream().mapToLong(b -> b.length).sum();
        Intake intake = intakes.save(new Intake(c, t, plannedStart, kept.size(), bytes, actor.person()));
        kept.forEach((name, content) -> documents.save(new IntakeDocument(intake.getId(), name, sha256(content), content)));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", c);
        details.put("documents", kept.size());
        details.put("bytes", bytes);
        audit.append(actor, "INTAKE_RECEIVED", "intake", intake.getId(), details, PROJECT);
        return view(intake);
    }

    @Transactional(readOnly = true)
    public List<View> recent() {
        return intakes.findTop50ByOrderByIdDesc().stream().map(IntakeService::view).toList();
    }

    /** The next job for a worker, or empty; the job is marked RUNNING in the same transaction. */
    @Transactional
    public Optional<Job> claim() {
        return intakes.nextForWorker(Instant.now().minus(STALE)).map(i -> {
            i.start();
            List<DocumentRef> refs = documents.findByIntakeIdOrderByName(i.getId()).stream()
                    .map(d -> new DocumentRef(d.getName(), d.getSha256(), d.getSize())).toList();
            return new Job(i.getId(), i.getCode(), i.getTitle(), i.getPlannedStart(), refs);
        });
    }

    @Transactional(readOnly = true)
    public byte[] document(Long intakeId, String name) {
        return documents.findByIntakeIdAndName(intakeId, name).map(IntakeDocument::getContent)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document " + name + " of intake " + intakeId
                        + " not found"));
    }

    /** The worker's report. Only a running job can finish; the tender must be the one with the intake's code. */
    @Transactional
    public View finish(Actor actor, Long intakeId, boolean ok, Long tenderId, Integer proposals, String message) {
        Intake i = intakes.findById(intakeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "intake " + intakeId + " not found"));
        if (i.getStatus() != Intake.Status.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "intake " + intakeId + " is " + i.getStatus() + ", not RUNNING");
        }
        if (ok) {
            boolean matches = tenderId != null && tenders.findById(tenderId)
                    .map(t -> t.getCode().equals(i.getCode())).orElse(false);
            if (!matches) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "the tender does not match intake " + i.getCode());
            }
        }
        String short_ = message == null ? null : message.strip().substring(0, Math.min(message.strip().length(), 300));
        i.finish(ok, ok ? tenderId : null, proposals, short_);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", i.getCode());
        details.put("status", i.getStatus().name());
        details.put("proposals", proposals == null ? 0 : proposals);
        audit.append(actor, "INTAKE_ANALYSED", "intake", i.getId(), details, PROJECT);
        return view(i);
    }

    static boolean isPdf(byte[] b) {
        return b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-';
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static View view(Intake i) {
        return new View(i.getId(), i.getCode(), i.getTitle(), i.getPlannedStart(), i.getStatus(), i.getDocuments(),
                i.getBytes(), i.getUploadedBy(), i.getCreatedAt(), i.getStartedAt(), i.getFinishedAt(), i.getTenderId(),
                i.getProposals(), i.getMessage());
    }
}
