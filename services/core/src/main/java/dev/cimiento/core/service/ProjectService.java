package dev.cimiento.core.service;

import dev.cimiento.core.domain.Project;
import dev.cimiento.core.domain.Tender;
import dev.cimiento.core.repository.ProjectRepository;
import dev.cimiento.core.repository.TenderRepository;
import dev.cimiento.core.security.Role;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects and tenders. Creation is idempotent by code: repeating the same request returns the
 * existing record; a different request with the same code is a conflict.
 */
@Service
public class ProjectService {

    public record Created<T>(T value, boolean created) {
    }

    private final ProjectRepository projects;
    private final TenderRepository tenders;
    private final AuditService audit;

    public ProjectService(ProjectRepository projects, TenderRepository tenders, AuditService audit) {
        this.projects = projects;
        this.tenders = tenders;
        this.audit = audit;
    }

    @Transactional
    public Created<Project> createProject(Role actor, String code, String name) {
        var existing = projects.findByCode(code);
        if (existing.isPresent()) {
            if (!existing.get().getName().equals(name)) {
                throw new ApiException(HttpStatus.CONFLICT, "project " + code + " exists with other data");
            }
            return new Created<>(existing.get(), false);
        }
        Project p = projects.save(new Project(code, name));
        audit.append(actor, "PROJECT_CREATED", "project", p.getId(), Map.of("code", code));
        return new Created<>(p, true);
    }

    @Transactional
    public Created<Tender> createTender(Role actor, String projectCode, String code, String title, Integer termMonths,
                                        LocalDate plannedStart) {
        Project project = projects.findByCode(projectCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project " + projectCode + " not found"));
        var existing = tenders.findByCode(code);
        if (existing.isPresent()) {
            Tender t = existing.get();
            boolean same = t.getProject().getId().equals(project.getId()) && t.getTitle().equals(title)
                    && java.util.Objects.equals(t.getContractTermMonths(), termMonths) && t.getPlannedStart().equals(plannedStart);
            if (!same) {
                throw new ApiException(HttpStatus.CONFLICT, "tender " + code + " exists with other data");
            }
            return new Created<>(t, false);
        }
        Tender t = tenders.save(new Tender(project, code, title, termMonths, plannedStart));
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("code", code);
        details.put("project", projectCode);
        details.put("contractTermMonths", termMonths);
        audit.append(actor, "TENDER_CREATED", "tender", t.getId(), details);
        return new Created<>(t, true);
    }

    @Transactional(readOnly = true)
    public List<Project> listProjects() {
        return projects.findAll().stream().sorted((a, b) -> a.getCode().compareTo(b.getCode())).toList();
    }

    @Transactional(readOnly = true)
    public List<Tender> listTenders(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "project " + projectId + " not found");
        }
        return tenders.findByProjectIdOrderByCode(projectId);
    }

    @Transactional(readOnly = true)
    public Tender tender(Long id) {
        return tenders.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + id + " not found"));
    }
}
