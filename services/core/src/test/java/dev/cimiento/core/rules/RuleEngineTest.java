package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cimiento.core.rules.Payloads.Citation;
import dev.cimiento.core.rules.ProposalType.Route;
import dev.cimiento.core.rules.RuleEngine.Evaluation;
import dev.cimiento.core.rules.RuleEngine.TenderContext;
import dev.cimiento.core.rules.RuleResult.Outcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Citation> CITE = List.of(new Citation("mediciones", 1, "MT01 | Excavación"));
    private static final TenderContext TENDER = new TenderContext(LocalDate.of(2027, 1, 4), 2);
    private static final Map<String, Rate> RATES = Map.of(
            "MT01", new Rate("MT01", "A01", "m3", new BigDecimal("18.50"), new BigDecimal("60")),
            "IS01", new Rate("IS01", "A07", "m", new BigDecimal("45.00"), new BigDecimal("40")),
            "UR01", new Rate("UR01", "A12", "m2", new BigDecimal("28.00"), new BigDecimal("70")));
    private static final RuleEngine ENGINE = new RuleEngine(RATES, new BigDecimal("1000000"), MAPPER);

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Outcome outcome(Evaluation e, String rule) {
        return e.results().stream().filter(r -> r.rule().equals(rule)).findFirst().orElseThrow().outcome();
    }

    private static Evaluation eval(ProposalType type, String payload) {
        return ENGINE.evaluate(type, json(payload), CITE, List.of(), TENDER);
    }

    @Test
    void rulesAlwaysRunInTheSameOrder() {
        var e = eval(ProposalType.RISK_SIGNAL, "{'kind':'CONTRADICTION','severity':'LOW','description':'x'}");
        assertThat(e.results()).extracting(RuleResult::rule).containsExactly("PAYLOAD", "CITATIONS",
                "TAKEOFF_UNITS_AND_SUMS", "BUDGET", "SCHEDULE_GRAPH", "CRITICAL_PATH", "CONTRACT_TERM",
                "REVIEW_THRESHOLD");
    }

    // ------------------------------------------------------------ citations

    @Test
    void nothingRunsWithoutCitation() {
        var payload = json("{'kind':'X','severity':'LOW','description':'x'}");
        var none = ENGINE.evaluate(ProposalType.RISK_SIGNAL, payload, List.of(), List.of(), TENDER);
        var badPage = ENGINE.evaluate(ProposalType.RISK_SIGNAL, payload,
                List.of(new Citation("pliego", 0, "x")), List.of(), TENDER);
        var blank = ENGINE.evaluate(ProposalType.RISK_SIGNAL, payload,
                List.of(new Citation("pliego", 1, " ")), List.of(), TENDER);
        assertThat(List.of(none, badPage, blank)).allSatisfy(e -> {
            assertThat(outcome(e, "CITATIONS")).isEqualTo(Outcome.FAIL);
            assertThat(e.failed()).isTrue();
            assertThat(e.route()).isNull();
        });
    }

    // ------------------------------------------------------------ takeoff

    @Test
    void takeoffConvertsUnitsAndIgnoresTrueDuplicates() {
        var e = eval(ProposalType.TAKEOFF, """
                {'items':[
                  {'code':'MT01','unit':'m3','quantity':300.50,'rows':[
                     {'unitText':'m³','quantity':100.25},{'unitText':'M3','quantity':200.25},
                     {'unitText':'M3','quantity':200.25,'duplicate':true}]},
                  {'code':'IS01','unit':'m','quantity':1350,'rows':[
                     {'unitText':'km','quantity':1.2},{'unitText':'ml','quantity':150}]},
                  {'code':'UR01','unit':'m2','quantity':3500,'rows':[{'unitText':'ha','quantity':0.35}]}]}""");
        assertThat(outcome(e, "TAKEOFF_UNITS_AND_SUMS")).isEqualTo(Outcome.PASS);
        assertThat(e.route()).isEqualTo(Route.HUMAN);
    }

    @Test
    void takeoffRejectsWrongSumsUnitsAndFakeDuplicates() {
        String[] bad = {
            // sum mismatch
            "{'items':[{'code':'MT01','unit':'m3','quantity':301,'rows':[{'unitText':'m3','quantity':300}]}]}",
            // unknown spelling
            "{'items':[{'code':'MT01','unit':'m3','quantity':1,'rows':[{'unitText':'yd3','quantity':1}]}]}",
            // unit of another dimension
            "{'items':[{'code':'MT01','unit':'m3','quantity':1,'rows':[{'unitText':'m2','quantity':1}]}]}",
            // item unit differs from the company rate
            "{'items':[{'code':'MT01','unit':'m2','quantity':1,'rows':[{'unitText':'m2','quantity':1}]}]}",
            // duplicate that repeats nothing
            "{'items':[{'code':'MT01','unit':'m3','quantity':1,'rows':[{'unitText':'m3','quantity':1},"
                    + "{'unitText':'m3','quantity':2,'duplicate':true}]}]}",
            // unknown work item
            "{'items':[{'code':'ZZ99','unit':'m3','quantity':1,'rows':[{'unitText':'m3','quantity':1}]}]}",
            // same item twice
            "{'items':[{'code':'MT01','unit':'m3','quantity':1,'rows':[{'unitText':'m3','quantity':1}]},"
                    + "{'code':'MT01','unit':'m3','quantity':1,'rows':[{'unitText':'m3','quantity':1}]}]}",
        };
        for (String p : bad) {
            var e = eval(ProposalType.TAKEOFF, p);
            assertThat(outcome(e, "TAKEOFF_UNITS_AND_SUMS")).as(p).isEqualTo(Outcome.FAIL);
            assertThat(e.failed()).isTrue();
        }
    }

    @Test
    void takeoffToleratesOneCent() {
        var e = eval(ProposalType.TAKEOFF,
                "{'items':[{'code':'MT01','unit':'m3','quantity':10.01,'rows':[{'unitText':'m3','quantity':10}]}]}");
        assertThat(outcome(e, "TAKEOFF_UNITS_AND_SUMS")).isEqualTo(Outcome.PASS);
    }

    // ------------------------------------------------------------ budget

    @Test
    void budgetIsQuantityTimesCompanyPrice() {
        var e = eval(ProposalType.BUDGET, "{'lines':[{'code':'MT01','quantity':100.03},{'code':'IS01','quantity':10}]}");
        assertThat(outcome(e, "BUDGET")).isEqualTo(Outcome.PASS);
        // 100.03 x 18.50 = 1850.555 -> 1850.56 (half up); 10 x 45 = 450.00
        assertThat(e.budgetTotal()).isEqualByComparingTo("2300.56");
    }

    @Test
    void budgetRejectsWrongClaimedTotalAndUnknownItems() {
        var wrong = eval(ProposalType.BUDGET, "{'lines':[{'code':'MT01','quantity':10}],'claimedTotal':200}");
        var right = eval(ProposalType.BUDGET, "{'lines':[{'code':'MT01','quantity':10}],'claimedTotal':185.00}");
        var unknown = eval(ProposalType.BUDGET, "{'lines':[{'code':'NOPE','quantity':10}]}");
        assertThat(outcome(wrong, "BUDGET")).isEqualTo(Outcome.FAIL);
        assertThat(outcome(right, "BUDGET")).isEqualTo(Outcome.PASS);
        assertThat(outcome(unknown, "BUDGET")).isEqualTo(Outcome.FAIL);
    }

    @Test
    void largeBudgetHitsTheAmountThreshold() {
        var e = eval(ProposalType.BUDGET, "{'lines':[{'code':'MT01','quantity':60000}]}");  // 1,110,000.00
        assertThat(e.reasons()).contains("AMOUNT_OVER_THRESHOLD");
        assertThat(outcome(e, "REVIEW_THRESHOLD")).isEqualTo(Outcome.WARN);
    }

    // ------------------------------------------------------------ schedule

    @Test
    void scheduleGetsCriticalPathAndTermCheck() {
        var ok = eval(ProposalType.SCHEDULE, """
                {'activities':[{'code':'A','name':'a','duration':10},{'code':'B','name':'b','duration':5}],
                 'links':[{'from':'A','to':'B','lag':0}]}""");
        assertThat(outcome(ok, "SCHEDULE_GRAPH")).isEqualTo(Outcome.PASS);
        assertThat(ok.schedule().duration()).isEqualTo(15);
        assertThat(outcome(ok, "CONTRACT_TERM")).isEqualTo(Outcome.PASS); // ends 2027-01-22 < 2027-03-03

        var late = eval(ProposalType.SCHEDULE, """
                {'activities':[{'code':'A','name':'a','duration':60}],'links':[]}""");
        assertThat(outcome(late, "CONTRACT_TERM")).isEqualTo(Outcome.WARN); // ends 2027-03-26
        assertThat(late.reasons()).contains("CONTRACT_TERM");
        assertThat(late.route()).isEqualTo(Route.HUMAN);
    }

    @Test
    void cyclicScheduleFailsAndSkipsLaterScheduleRules() {
        var e = eval(ProposalType.SCHEDULE, """
                {'activities':[{'code':'A','duration':1},{'code':'B','duration':1}],
                 'links':[{'from':'A','to':'B','lag':0},{'from':'B','to':'A','lag':0}]}""");
        assertThat(outcome(e, "SCHEDULE_GRAPH")).isEqualTo(Outcome.FAIL);
        assertThat(outcome(e, "CRITICAL_PATH")).isEqualTo(Outcome.SKIP);
        assertThat(e.failed()).isTrue();
    }

    @Test
    void unknownContractTermWarnsAndForcesReview() {
        var e = ENGINE.evaluate(ProposalType.SCHEDULE, json("{'activities':[{'code':'A','duration':5}],'links':[]}"),
                CITE, List.of(), new TenderContext(LocalDate.of(2027, 1, 4), null));
        assertThat(outcome(e, "CONTRACT_TERM")).isEqualTo(Outcome.WARN);
        assertThat(e.failed()).isFalse();
        assertThat(e.route()).isEqualTo(Route.HUMAN);
        assertThat(e.reasons()).contains("CONTRACT_TERM");
    }

    @Test
    void termCheckNeedsAMondayStart() {
        var e = ENGINE.evaluate(ProposalType.SCHEDULE, json("{'activities':[{'code':'A','duration':1}],'links':[]}"),
                CITE, List.of(), new TenderContext(LocalDate.of(2027, 1, 5), 2));
        assertThat(outcome(e, "CONTRACT_TERM")).isEqualTo(Outcome.FAIL);
    }

    // ------------------------------------------------------------ payload and routing

    @Test
    void malformedPayloadsFail() {
        String[][] cases = {
            {"TAKEOFF", "{'items':[]}"},
            {"TAKEOFF", "{'items':[{'code':'MT01','unit':'m3','quantity':-1,'rows':[{'unitText':'m3','quantity':-1}]}]}"},
            {"BUDGET", "{'lines':[{'code':'MT01'}]}"},
            {"SCHEDULE", "{'activities':[{'code':'A','duration':1}]}"},
            {"PROGRESS_UPDATE", "{'week':1,'rows':[{'activity':'A','plannedPct':101,'actualPct':0}]}"},
            {"PROGRESS_UPDATE", "{'week':0,'rows':[{'activity':'A','plannedPct':1,'actualPct':0}]}"},
            {"RISK_SIGNAL", "{'kind':'X','severity':'EXTREME','description':'x'}"},
            {"BUDGET", "{'lines':[{'code':'MT01','quantity':1}],'unexpected':true}"},
        };
        for (String[] c : cases) {
            var e = eval(ProposalType.valueOf(c[0]), c[1]);
            assertThat(outcome(e, "PAYLOAD")).as(c[1]).isEqualTo(Outcome.FAIL);
            assertThat(e.route()).isNull();
        }
        var notObject = ENGINE.evaluate(ProposalType.REPORT, json("[1,2]"), CITE, List.of(), TENDER);
        assertThat(outcome(notObject, "PAYLOAD")).isEqualTo(Outcome.FAIL);
    }

    @Test
    void routingTable() {
        var lowRisk = eval(ProposalType.RISK_SIGNAL, "{'kind':'CONTRADICTION','severity':'LOW','description':'x'}");
        assertThat(lowRisk.route()).isEqualTo(Route.AUTO);

        var highRisk = eval(ProposalType.RISK_SIGNAL, "{'kind':'CONTRADICTION','severity':'HIGH','description':'x'}");
        assertThat(highRisk.route()).isEqualTo(Route.HUMAN);
        assertThat(highRisk.reasons()).contains("HIGH_RISK");

        var injection = eval(ProposalType.RISK_SIGNAL, "{'kind':'INJECTION','severity':'LOW','description':'x'}");
        assertThat(injection.route()).isEqualTo(Route.HUMAN);
        assertThat(injection.reasons()).contains(RuleEngine.SIGNAL_INJECTION);

        var onPlan = eval(ProposalType.PROGRESS_UPDATE,
                "{'week':3,'rows':[{'activity':'A01','plannedPct':50,'actualPct':50}]}");
        assertThat(onPlan.route()).isEqualTo(Route.AUTO);
        var behind = eval(ProposalType.PROGRESS_UPDATE,
                "{'week':3,'rows':[{'activity':'A01','plannedPct':50,'actualPct':20}]}");
        assertThat(behind.route()).isEqualTo(Route.HUMAN);

        var report = eval(ProposalType.REPORT, "{'text':'x'}");
        assertThat(report.route()).isEqualTo(Route.HUMAN);
        assertThat(report.reasons()).first().isEqualTo("TYPE_REQUIRES_REVIEW");
    }

    @Test
    void injectionSignalForcesReviewOnAnyType() {
        var e = ENGINE.evaluate(ProposalType.PROGRESS_UPDATE,
                json("{'week':3,'rows':[{'activity':'A01','plannedPct':50,'actualPct':50}]}"), CITE,
                List.of(RuleEngine.SIGNAL_INJECTION), TENDER);
        assertThat(e.route()).isEqualTo(Route.HUMAN);
        assertThat(e.reasons()).containsExactly(RuleEngine.SIGNAL_INJECTION);
    }

    @Test
    void noProposalTypeIsAutomaticForDocumentsBudgetsOrSchedules() {
        for (ProposalType t : ProposalType.values()) {
            if (t != ProposalType.RISK_SIGNAL && t != ProposalType.PROGRESS_UPDATE) {
                assertThat(t.defaultRoute()).as(t.name()).isEqualTo(Route.HUMAN);
            }
        }
    }
}
