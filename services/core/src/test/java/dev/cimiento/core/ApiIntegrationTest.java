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

    private String bidAnalysis(String deadline) {
        return "{'tenderId':" + tenderId + ",'type':'BID_ANALYSIS','citations':[{'document':'pliego','page':1,"
                + "'text':'Plazo de presentación'}],'payload':{'fields':{'fecha_presentacion':'" + deadline + "'}}}";
    }

    private JsonNode portfolioItem() throws Exception {
        for (JsonNode n : body(call("GET", "/api/portfolio", ANALYST, null, null).andExpect(status().isOk()))) {
            if (n.get("tenderId").asLong() == tenderId) {
                return n;
            }
        }
        throw new AssertionError("tender not in portfolio");
    }

    private ResultActions move(String key, String stage, String note) throws Exception {
        String body = note == null ? "{'stage':'" + stage + "'}" : "{'stage':'" + stage + "','note':'" + note + "'}";
        return call("POST", "/api/tenders/" + tenderId + "/stage", key, body, null);
    }

    @Test
    void tenderMovesThroughThePipelineUnderTheCoreRules() throws Exception {
        JsonNode item = portfolioItem();
        assertThat(item.get("stage").asText()).isEqualTo("TO_REVIEW");
        assertThat(item.get("bidDeadline").isNull()).isTrue();

        long bid = body(call("POST", "/api/proposals", AGENT, bidAnalysis("2027-02-15"), UUID.randomUUID().toString())
                .andExpect(status().isCreated())).get("id").asLong();
        item = portfolioItem();
        assertThat(item.get("bidDeadline").asText()).isEqualTo("2027-02-15");
        assertThat(item.get("deadlineApproved").asBoolean()).isFalse();
        assertThat(item.get("pendingReviews").asInt()).isEqualTo(1);

        // only a reviewer moves tenders
        move(AGENT, "UNDER_REVIEW", null).andExpect(status().isForbidden());
        move(ANALYST, "UNDER_REVIEW", null).andExpect(status().isForbidden());
        move(REVIEWER, "SUBMITTED", null).andExpect(status().isUnprocessableEntity());
        move(REVIEWER, "UNDER_REVIEW", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("UNDER_REVIEW"));
        move(REVIEWER, "DECISION_PENDING", null).andExpect(status().isOk());
        // deciding to bid needs a reason and an approved bid analysis
        move(REVIEWER, "IN_PREPARATION", null).andExpect(status().isUnprocessableEntity());
        move(REVIEWER, "IN_PREPARATION", "encaja").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0]").value("deciding to bid needs an approved bid analysis"));
        call("POST", "/api/proposals/" + bid + "/decision", REVIEWER, "{'outcome':'APPROVE'}", null)
                .andExpect(status().isOk());
        assertThat(portfolioItem().get("deadlineApproved").asBoolean()).isTrue();
        move(REVIEWER, "IN_PREPARATION", "encaja en solvencia y margen").andExpect(status().isOk());

        // submitting waits for every proposal of the tender to be decided
        long sched = body(call("POST", "/api/proposals", AGENT, schedule(""), UUID.randomUUID().toString()))
                .get("id").asLong();
        move(REVIEWER, "SUBMITTED", null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0]").value("1 proposal(s) still wait for review"));
        call("POST", "/api/proposals/" + sched + "/decision", REVIEWER, "{'outcome':'APPROVE'}", null);
        move(REVIEWER, "SUBMITTED", null).andExpect(status().isOk());
        move(REVIEWER, "AWARDED", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.next").isEmpty());
        move(REVIEWER, "LOST", "x").andExpect(status().isUnprocessableEntity());

        JsonNode history = body(call("GET", "/api/tenders/" + tenderId + "/stages", ANALYST, null, null));
        assertThat(history).hasSize(5);
        assertThat(history.get(2).get("to").asText()).isEqualTo("IN_PREPARATION");
        assertThat(history.get(2).get("note").asText()).isEqualTo("encaja en solvencia y margen");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entry WHERE action = 'TENDER_STAGE_CHANGED' "
                + "AND entity_id = ?", Long.class, String.valueOf(tenderId))).isEqualTo(5);
        // the reason stays out of the audit chain (and so out of the event stream)
        String details = String.join("\n", jdbc.queryForList(
                "SELECT details FROM audit_entry WHERE action = 'TENDER_STAGE_CHANGED'", String.class));
        assertThat(details).doesNotContain("solvencia");
        call("GET", "/api/audit/verify", ANALYST, null, null).andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void schedulesAndBudgetsExportAsFiles() throws Exception {
        long sched = body(call("POST", "/api/proposals", AGENT, schedule(""), UUID.randomUUID().toString()))
                .get("id").asLong();
        var draft = call("GET", "/api/proposals/" + sched + "/export", ANALYST, null, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("-borrador.xml")))
                .andReturn().getResponse();
        assertThat(draft.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("<Finish>2027-01-22T17:00:00</Finish>").contains("Cimentación");
        call("POST", "/api/proposals/" + sched + "/decision", REVIEWER, "{'outcome':'APPROVE'}", null);
        call("GET", "/api/proposals/" + sched + "/export", ANALYST, null, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("borrador"))));

        String code = jdbc.queryForObject("SELECT code FROM rate ORDER BY code LIMIT 1", String.class);
        String total = jdbc.queryForObject("SELECT unit_price * 10 FROM rate WHERE code = ?",
                java.math.BigDecimal.class, code).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        String budget = "{'tenderId':" + tenderId + ",'type':'BUDGET','citations':[{'document':'mediciones',"
                + "'page':1,'text':'x'}],'payload':{'lines':[{'code':'" + code + "','quantity':10}],'claimedTotal':'"
                + total + "'}}";
        long b = body(call("POST", "/api/proposals", AGENT, budget, UUID.randomUUID().toString())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))).get("id").asLong();
        byte[] bc3 = call("GET", "/api/proposals/" + b + "/export", ANALYST, null, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".bc3")))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(bc3, dev.cimiento.core.rules.Exports.BC3_CHARSET);
        assertThat(text).startsWith("~V|").contains("~D|").contains("~C|" + code + "|").contains("|" + total + "|");

        // failed or missing proposals have no export
        long failed = body(call("POST", "/api/proposals", AGENT, schedule(",{'from':'A02','to':'A01','lag':0}"),
                UUID.randomUUID().toString())).get("id").asLong();
        call("GET", "/api/proposals/" + failed + "/export", ANALYST, null, null).andExpect(status().isConflict());
        call("GET", "/api/proposals/9999999/export", ANALYST, null, null).andExpect(status().isNotFound());
    }

    @Test
    void siteViewComesFromTheLatestScheduleAndProgress() throws Exception {
        call("GET", "/api/tenders/" + tenderId + "/site", ANALYST, null, null).andExpect(status().isNotFound());
        call("POST", "/api/proposals", AGENT, schedule(""), UUID.randomUUID().toString());
        String progress = "{'tenderId':" + tenderId + ",'type':'PROGRESS_UPDATE','citations':[{'document':"
                + "'obra/avance_semanal.csv','page':1,'text':'2;2027-01-15;A01'}],'payload':{'week':2,'rows':["
                + "{'activity':'A01','plannedPct':100,'actualPct':100},{'activity':'A02','plannedPct':50,'actualPct':20}],"
                + "'history':[{'week':1,'rows':[{'activity':'A01','plannedPct':100,'actualPct':80}]}]}}";
        call("POST", "/api/proposals", AGENT, progress, UUID.randomUUID().toString())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        var site = body(call("GET", "/api/tenders/" + tenderId + "/site", ANALYST, null, null)
                .andExpect(status().isOk()));
        assertThat(site.get("statusWeek").asInt()).isEqualTo(2);
        assertThat(site.get("cutDate").asText()).isEqualTo("2027-01-15");
        assertThat(site.get("progressApproved").asBoolean()).isFalse();
        JsonNode a = site.get("analysis");
        assertThat(a.get("curve")).hasSize(2);
        // A02 started at day 5 and should be at 50 %; it is at 20 % of 10 days: 3 days late on the critical path
        JsonNode a02 = a.get("deviations").get(1);
        assertThat(a02.get("activity").asText()).isEqualTo("A02");
        assertThat(a02.get("delayDays").asInt()).isEqualTo(3);
        assertThat(a02.get("alarm").asBoolean()).isTrue();
        assertThat(a.get("impactDays").asInt()).isEqualTo(3);
        assertThat(site.get("plannedEnd").asText()).isEqualTo("2027-01-22");
        assertThat(site.get("forecastEnd").asText()).isEqualTo("2027-01-27");

        // history weeks must come before the status week
        String bad = progress.replace("'history':[{'week':1", "'history':[{'week':5");
        call("POST", "/api/proposals", AGENT, bad, UUID.randomUUID().toString())
                .andExpect(jsonPath("$.status").value("RULES_FAILED"));
    }

    @Test
    void dashboardAndPortfolioItemAreComputedByTheCore() throws Exception {
        String code = jdbc.queryForObject("SELECT code FROM rate ORDER BY code LIMIT 1", String.class);
        String total = jdbc.queryForObject("SELECT unit_price * 4 FROM rate WHERE code = ?",
                java.math.BigDecimal.class, code).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        call("POST", "/api/proposals", AGENT, "{'tenderId':" + tenderId + ",'type':'BUDGET','citations':[{'document':"
                + "'mediciones','page':1,'text':'x'}],'payload':{'lines':[{'code':'" + code + "','quantity':4}],"
                + "'claimedTotal':'" + total + "'}}", UUID.randomUUID().toString());
        var item = body(call("GET", "/api/portfolio/" + tenderId, ANALYST, null, null).andExpect(status().isOk()));
        assertThat(item.get("budgetTotal").decimalValue()).isEqualByComparingTo(total);
        call("GET", "/api/portfolio/9999999", ANALYST, null, null).andExpect(status().isNotFound());

        var d = body(call("GET", "/api/dashboard", ANALYST, null, null).andExpect(status().isOk()));
        assertThat(d.get("tendersByStage").get("TO_REVIEW").asInt()).isPositive();
        assertThat(d.get("open").asInt()).isPositive();
        assertThat(d.get("pendingByType").get("BUDGET").asInt()).isPositive();
        assertThat(d.get("openBudget").decimalValue()).isGreaterThanOrEqualTo(new java.math.BigDecimal(total));
        // reading the dashboard writes nothing
        call("GET", "/api/audit/verify", ANALYST, null, null).andExpect(jsonPath("$.valid").value(true));
    }
}
