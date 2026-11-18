package dev.cimiento.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** End to end through HTTP and a real Postgres (same image as docker compose). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {

    // Random per run: no key literal lives in the repository.
    static final String AGENT = "agent-" + UUID.randomUUID();
    static final String ANALYST = "analyst-" + UUID.randomUUID();
    static final String REVIEWER = "reviewer-" + UUID.randomUUID();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("cimiento.keys.agent", () -> AGENT);
        registry.add("cimiento.keys.analyst", () -> ANALYST);
        registry.add("cimiento.keys.reviewer", () -> REVIEWER);
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    long tenderId;

    private ResultActions call(String method, String path, String key, String body, String idem) throws Exception {
        var req = method.equals("GET") ? get(path) : post(path).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : body.replace('\'', '"'));
        if (key != null) {
            req.header("X-Api-Key", key);
        }
        if (idem != null) {
            req.header("Idempotency-Key", idem);
        }
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String schedule(String extraLink) {
        return "{'tenderId':" + tenderId + ",'type':'SCHEDULE','citations':[{'document':'mediciones','page':1,"
                + "'text':'MT01 | Excavación'}],'payload':{'activities':[{'code':'A01','name':'Tierras','duration':5},"
                + "{'code':'A02','name':'Cimentación','duration':10},{'code':'A03','name':'Estructura','duration':3}],"
                + "'links':[{'from':'A01','to':'A02','lag':0},{'from':'A01','to':'A03','lag':0}" + extraLink + "]}}";
    }

    @BeforeEach
    void tender() throws Exception {
        call("POST", "/api/projects", REVIEWER, "{'code':'P-1','name':'Proyecto de prueba'}", null);
        String code = "T-" + UUID.randomUUID();
        var r = call("POST", "/api/tenders", AGENT, "{'projectCode':'P-1','code':'" + code
                + "','title':'Licitación','contractTermMonths':3,'plannedStart':'2027-01-04'}", null)
                .andExpect(status().isCreated());
        tenderId = body(r).get("id").asLong();
    }

    @Test
    void keysAndRolesAreEnforced() throws Exception {
        call("GET", "/api/projects", null, null, null).andExpect(status().isUnauthorized());
        call("GET", "/api/projects", "wrong-key-wrong-key-123", null, null).andExpect(status().isUnauthorized());
        call("GET", "/api/projects", ANALYST, null, null).andExpect(status().isOk());
        // an analyst cannot write anything
        call("POST", "/api/proposals", ANALYST, schedule(""), "k1").andExpect(status().isForbidden());
        call("POST", "/api/projects", ANALYST, "{'code':'X','name':'x'}", null).andExpect(status().isForbidden());
        call("POST", "/api/tenders", ANALYST, "{}", null).andExpect(status().isForbidden());
        call("POST", "/api/proposals/1/decision", ANALYST, "{'outcome':'APPROVE'}", null)
                .andExpect(status().isForbidden());
        // an agent cannot approve, a reviewer cannot propose
        call("POST", "/api/proposals/1/decision", AGENT, "{'outcome':'APPROVE'}", null)
                .andExpect(status().isForbidden());
        call("POST", "/api/proposals", REVIEWER, schedule(""), "k2").andExpect(status().isForbidden());
        // unlisted routes are denied
        call("POST", "/api/audit/verify", REVIEWER, null, null).andExpect(status().isForbidden());
        // health stays public
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void scheduleWaitsForReviewThenExecutes() throws Exception {
        String key = UUID.randomUUID().toString();
        var submitted = call("POST", "/api/proposals", AGENT, schedule(""), key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.route").value("HUMAN"));
        long id = body(submitted).get("id").asLong();

        // same key, same body: same proposal, nothing new is created
        call("POST", "/api/proposals", AGENT, schedule(""), key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id));
        // same key, other body: conflict
        call("POST", "/api/proposals", AGENT, schedule(",{'from':'A02','to':'A03','lag':0}"), key)
                .andExpect(status().isConflict());
        call("POST", "/api/proposals", AGENT, schedule(""), null).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proposal WHERE tender_id = ?", Long.class, tenderId))
                .isEqualTo(1);

        // no schedule exists until a person approves
        call("GET", "/api/tenders/" + tenderId + "/schedule", ANALYST, null, null).andExpect(status().isNotFound());
        call("POST", "/api/proposals/" + id + "/decision", REVIEWER, "{'outcome':'APPROVE','comment':'ok'}", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
        call("GET", "/api/tenders/" + tenderId + "/schedule", ANALYST, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationDays").value(15))
                .andExpect(jsonPath("$.critical[0]").value("A01"))
                .andExpect(jsonPath("$.critical[1]").value("A02"))
                .andExpect(jsonPath("$.endDate").value("2027-01-22"))
                .andExpect(jsonPath("$.exceedsTerm").value(false));
        // a decision is final
        call("POST", "/api/proposals/" + id + "/decision", REVIEWER, "{'outcome':'REJECT'}", null)
                .andExpect(status().isConflict());
    }

    @Test
    void failedRulesBlockExecution() throws Exception {
        var r = call("POST", "/api/proposals", AGENT, schedule(",{'from':'A02','to':'A01','lag':0}"),
                UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RULES_FAILED"));
        long id = body(r).get("id").asLong();
        call("POST", "/api/proposals/" + id + "/decision", REVIEWER, "{'outcome':'APPROVE'}", null)
                .andExpect(status().isConflict());

        String noCitation = "{'tenderId':" + tenderId + ",'type':'RISK_SIGNAL','payload':{'kind':'X',"
                + "'severity':'LOW','description':'x'}}";
        call("POST", "/api/proposals", AGENT, noCitation, UUID.randomUUID().toString())
                .andExpect(jsonPath("$.status").value("RULES_FAILED"));
    }

    @Test
    void onlyRecordOnlyTypesRunAutomatically() throws Exception {
        String risk = "{'tenderId':" + tenderId + ",'type':'RISK_SIGNAL','citations':[{'document':'pliego','page':2,"
                + "'text':'plazo'}],'payload':{'kind':'CONTRADICTION','severity':'LOW','description':'plazo'}%s}";
        call("POST", "/api/proposals", AGENT, risk.formatted(""), UUID.randomUUID().toString())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.route").value("AUTO"));
        call("POST", "/api/proposals", AGENT, risk.formatted(",'signals':['INJECTION_DETECTED']"),
                UUID.randomUUID().toString())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.routingReasons[0]").value("INJECTION_DETECTED"));
    }

    @Test
    void correctionIsCheckedByTheRulesBeforeExecution() throws Exception {
        long id = body(call("POST", "/api/proposals", AGENT, schedule(""), UUID.randomUUID().toString()))
                .get("id").asLong();
        String badCorrection = "{'outcome':'CORRECT','correctedPayload':{'activities':[{'code':'A01','duration':0}],"
                + "'links':[]}}";
        call("POST", "/api/proposals/" + id + "/decision", REVIEWER, badCorrection, null)
                .andExpect(status().isUnprocessableEntity());
        call("GET", "/api/proposals/" + id, ANALYST, null, null)
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM decision WHERE proposal_id = ?", Long.class, id))
                .isZero();

        String goodCorrection = "{'outcome':'CORRECT','comment':'menos días','correctedPayload':{'activities':"
                + "[{'code':'A01','name':'Tierras','duration':2}],'links':[]}}";
        call("POST", "/api/proposals/" + id + "/decision", REVIEWER, goodCorrection, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.executedPayload.activities[0].duration").value(2));
        call("GET", "/api/tenders/" + tenderId + "/schedule", ANALYST, null, null)
                .andExpect(jsonPath("$.durationDays").value(2));
    }

    @Test
    void tenderWithUnknownTermStillGetsAReviewedSchedule() throws Exception {
        var r = call("POST", "/api/tenders", AGENT, "{'projectCode':'P-1','code':'T-NOTERM-" + UUID.randomUUID()
                + "','title':'Sin plazo','plannedStart':'2027-01-04'}", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractTermMonths").value(org.hamcrest.Matchers.nullValue()));
        long noTerm = body(r).get("id").asLong();
        String sched = schedule("").replace("'tenderId':" + tenderId, "'tenderId':" + noTerm);
        long id = body(call("POST", "/api/proposals", AGENT, sched, UUID.randomUUID().toString())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.routingReasons").value(org.hamcrest.Matchers.hasItem("CONTRACT_TERM"))))
                .get("id").asLong();
        call("POST", "/api/proposals/" + id + "/decision", REVIEWER, "{'outcome':'APPROVE'}", null)
                .andExpect(jsonPath("$.status").value("EXECUTED"));
        call("GET", "/api/tenders/" + noTerm + "/schedule", ANALYST, null, null)
                .andExpect(jsonPath("$.durationDays").value(15))
                .andExpect(jsonPath("$.contractDeadline").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.exceedsTerm").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void tenderCreationIsIdempotentByCode() throws Exception {
        String t = "{'projectCode':'P-1','code':'T-IDEM','title':'A','contractTermMonths':3,'plannedStart':'2027-01-04'}";
        call("POST", "/api/tenders", AGENT, t, null);
        call("POST", "/api/tenders", AGENT, t, null).andExpect(status().isOk());
        call("POST", "/api/tenders", AGENT, t.replace("'A'", "'B'"), null).andExpect(status().isConflict());
    }

    @Test
    void malformedRequestsAreRejected() throws Exception {
        call("POST", "/api/proposals", AGENT, "{'tenderId':" + tenderId + ",'type':'NOPE','payload':{}}", "x")
                .andExpect(status().isBadRequest());
        call("POST", "/api/proposals", AGENT, "not json", "y").andExpect(status().isBadRequest());
        call("POST", "/api/proposals", AGENT, "{'tenderId':999999,'type':'REPORT','payload':{},"
                + "'citations':[{'document':'pliego','page':1,'text':'x'}]}", "z").andExpect(status().isNotFound());
    }

    @Test
    void auditIsAppendOnlyAndTamperingIsDetected() throws Exception {
        call("POST", "/api/proposals", AGENT, schedule(""), UUID.randomUUID().toString());
        call("GET", "/api/audit/verify", ANALYST, null, null)
                .andExpect(jsonPath("$.valid").value(true));

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_entry SET action = 'X'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_entry")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_entry")).hasMessageContaining("append-only");

        // Simulate an attacker with owner rights who disables the trigger: the chain still exposes it.
        Long seq = jdbc.queryForObject("SELECT min(seq) FROM audit_entry", Long.class);
        String original = jdbc.queryForObject("SELECT details FROM audit_entry WHERE seq = ?", String.class, seq);
        jdbc.execute("ALTER TABLE audit_entry DISABLE TRIGGER audit_entry_no_update_delete");
        try {
            jdbc.update("UPDATE audit_entry SET details = '{\"forged\":true}' WHERE seq = ?", seq);
            call("GET", "/api/audit/verify", ANALYST, null, null)
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.firstBrokenSeq").value(seq));
            jdbc.update("UPDATE audit_entry SET details = ? WHERE seq = ?", original, seq);
        } finally {
            jdbc.execute("ALTER TABLE audit_entry ENABLE TRIGGER audit_entry_no_update_delete");
        }
        call("GET", "/api/audit/verify", ANALYST, null, null).andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void auditDetailsCarryNoDocumentText() throws Exception {
        call("POST", "/api/proposals", AGENT, schedule(""), UUID.randomUUID().toString());
        String all = String.join("\n", jdbc.queryForList("SELECT details FROM audit_entry", String.class));
        assertThat(all).doesNotContain("Excavación").doesNotContain("Tierras");
    }
}
