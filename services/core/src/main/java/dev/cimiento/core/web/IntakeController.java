package dev.cimiento.core.web;

import dev.cimiento.core.security.Actor;
import dev.cimiento.core.security.ApiKeyFilter;
import dev.cimiento.core.service.ApiException;
import dev.cimiento.core.service.IntakeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Tender intake: upload from the console, claim and report from the agent worker (ADR 0018). */
@RestController
@RequestMapping("/api/intakes")
public class IntakeController {

    public record Result(@NotNull Boolean ok, Long tenderId, Integer proposals, @Size(max = 300) String message) {
    }

    private final IntakeService intakes;

    public IntakeController(IntakeService intakes) {
        this.intakes = intakes;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IntakeService.View> upload(@RequestAttribute(ApiKeyFilter.ACTOR_ATTRIBUTE) Actor actor,
                                                     @RequestParam String code, @RequestParam String title,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedStart,
                                                     @RequestParam Map<String, MultipartFile> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        try {
            for (var e : files.entrySet()) {
                bytes.put(e.getKey(), e.getValue().getBytes());
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "the upload could not be read");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(intakes.receive(actor, code, title, plannedStart, bytes));
    }

    @GetMapping
    public List<IntakeService.View> recent() {
        return intakes.recent();
    }

    @PostMapping("/claim")
    public ResponseEntity<IntakeService.Job> claim() {
        return intakes.claim().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping(value = "/{id}/documents/{name}", produces = MediaType.APPLICATION_PDF_VALUE)
    public byte[] document(@PathVariable Long id, @PathVariable String name) {
        return intakes.document(id, name);
    }

    @PostMapping("/{id}/result")
    public IntakeService.View result(@RequestAttribute(ApiKeyFilter.ACTOR_ATTRIBUTE) Actor actor, @PathVariable Long id,
                                     @Valid @RequestBody Result body) {
        return intakes.finish(actor, id, body.ok(), body.tenderId(), body.proposals(), body.message());
    }
}
