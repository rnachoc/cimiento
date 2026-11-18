package dev.cimiento.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cimiento.core.domain.Decision;
import dev.cimiento.core.repository.OutboxRepository;
import dev.cimiento.core.rules.Payloads.Citation;
import dev.cimiento.core.rules.ProposalType;
import dev.cimiento.core.rules.PersonRole;
import dev.cimiento.core.security.Actor;
import dev.cimiento.core.security.Role;
import dev.cimiento.core.service.OutboxPublisher;
import dev.cimiento.core.service.ProjectService;
import dev.cimiento.core.service.ProposalService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The transactional outbox against a real Postgres, with the Kafka producer replaced by a
 * recorder. A real broker is exercised by the end-to-end flow (scripts/flow.sh, CI).
 */
@SpringBootTest(properties = {"cimiento.outbox.enabled=true", "cimiento.outbox.interval-ms=100"})
@Testcontainers
class OutboxKafkaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("cimiento.keys.agent", () -> "agent-" + UUID.randomUUID());
        r.add("cimiento.keys.analyst", () -> "analyst-" + UUID.randomUUID());
        r.add("cimiento.keys.reviewer", () -> "reviewer-" + UUID.randomUUID());
    }

    @MockitoBean
    KafkaTemplate<String, String> kafka;

    @Autowired
    ProjectService projects;

    @Autowired
    ProposalService proposals;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    ObjectMapper mapper;

    private final List<String[]> sent = java.util.Collections.synchronizedList(new ArrayList<>());

    private void brokerAccepts() {
        when(kafka.send(eq(OutboxPublisher.TOPIC), anyString(), anyString())).thenAnswer(inv -> {
            sent.add(new String[] {inv.getArgument(1), inv.getArgument(2)});
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });
    }

    private void waitUntil(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!ok.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    @Test
    void auditEventsArePublishedKeyedByProjectInOrderAndRetriedOnFailure() throws Exception {
        // 1. broker down: events stay in the outbox (nothing is lost)
        when(kafka.send(eq(OutboxPublisher.TOPIC), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        String project = "P-" + UUID.randomUUID();
        projects.createProject(Role.REVIEWER, project, "Proyecto");
        Thread.sleep(500);
        assertThat(outbox.countByPublishedAtIsNull()).isGreaterThanOrEqualTo(1);

        // 2. broker back: the same events are sent, then the rest of the flow
        reset(kafka);
        brokerAccepts();
        var tender = projects.createTender(Role.AGENT, project, "T-" + UUID.randomUUID(), "Licitación", 3,
                LocalDate.of(2027, 1, 4)).value();
        JsonNode payload = mapper.readTree("{\"activities\":[{\"code\":\"A01\",\"name\":\"Tierras\",\"duration\":5}],"
                + "\"links\":[]}");
        var p = proposals.submit(Role.AGENT, UUID.randomUUID().toString(), tender.getId(), ProposalType.SCHEDULE,
                payload, List.of(new Citation("mediciones", 1, "MT01 | Excavación en zanjas")), List.of()).proposal();
        proposals.decide(new Actor(Role.REVIEWER, "jefe.obra", PersonRole.SITE_MANAGER), p.getId(), Decision.Outcome.APPROVE, "ok", null);
        waitUntil(() -> outbox.countByPublishedAtIsNull() == 0);
        assertThat(outbox.countByPublishedAtIsNull()).isZero();

        List<String> actions = new ArrayList<>();
        long lastSeq = 0;
        for (String[] rec : sent) {
            if (!project.equals(rec[0])) {
                continue;
            }
            JsonNode e = mapper.readTree(rec[1]);
            actions.add(e.get("action").asText());
            assertThat(e.get("seq").asLong()).isGreaterThan(lastSeq);
            lastSeq = e.get("seq").asLong();
            assertThat(e.get("key").asText()).isEqualTo(project);
            assertThat(rec[1]).doesNotContain("Excavación").doesNotContain("Tierras");
        }
        assertThat(actions).containsExactly("PROJECT_CREATED", "TENDER_CREATED", "PROPOSAL_SUBMITTED",
                "DECISION_RECORDED", "PROPOSAL_EXECUTED");
    }
}
