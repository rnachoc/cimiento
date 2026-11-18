package dev.cimiento.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cimiento.core.domain.Proposal;
import dev.cimiento.core.domain.ProposalStatus;
import dev.cimiento.core.domain.RateEntity;
import dev.cimiento.core.repository.ProposalRepository;
import dev.cimiento.core.repository.RateRepository;
import dev.cimiento.core.rules.Exports;
import dev.cimiento.core.rules.Payloads;
import dev.cimiento.core.rules.ProposalType;
import dev.cimiento.core.rules.Rate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Downloads of a schedule (MS Project XML) or a budget (BC3). Only proposals whose rules passed
 * can be exported; the file is built from what was executed (a correction, if any), otherwise from
 * the proposal, and says which in its name.
 */
@Service
public class ExportService {

    public record ExportFile(String filename, String contentType, byte[] content) {
    }

    private final ProposalRepository proposals;
    private final RateRepository rates;
    private final CanonicalJson json;

    public ExportService(ProposalRepository proposals, RateRepository rates, CanonicalJson json) {
        this.proposals = proposals;
        this.rates = rates;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ExportFile export(Long proposalId) {
        Proposal p = proposals.findById(proposalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "proposal " + proposalId + " not found"));
        if (p.getStatus() == ProposalStatus.RULES_FAILED || p.getStatus() == ProposalStatus.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT, "a " + p.getStatus() + " proposal cannot be exported");
        }
        boolean executed = p.getStatus() == ProposalStatus.EXECUTED;
        JsonNode content = json.read(executed && p.getExecutedPayload() != null ? p.getExecutedPayload() : p.getPayload());
        String tender = p.getTender().getCode();
        String suffix = tender + "-" + p.getId() + (executed ? "" : "-borrador");
        try {
            if (p.getType() == ProposalType.SCHEDULE) {
                var schedule = json.mapper().treeToValue(content, Payloads.Schedule.class);
                String xml = Exports.mspdi("Planning " + tender, p.getTender().getPlannedStart(), schedule);
                return new ExportFile("planning-" + suffix + ".xml", "application/xml",
                        xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (p.getType() == ProposalType.BUDGET) {
                var budget = json.mapper().treeToValue(content, Payloads.Budget.class);
                var entities = rates.findAll();
                Map<String, Rate> table = entities.stream().map(RateEntity::toRate)
                        .collect(Collectors.toMap(Rate::code, r -> r));
                Map<String, String> descriptions = entities.stream()
                        .collect(Collectors.toMap(RateEntity::getCode, RateEntity::getDescription));
                var lines = Exports.price(budget.lines(), table, descriptions);
                String bc3 = Exports.bc3(tender, "Presupuesto " + tender,
                        p.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), lines);
                return new ExportFile("presupuesto-" + suffix + ".bc3", "text/plain; charset=windows-1252",
                        bc3.getBytes(Exports.BC3_CHARSET));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "proposal cannot be exported: " + e.getMessage());
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, p.getType() + " proposals have no file export");
    }
}
