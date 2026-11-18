package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cimiento.core.rules.Payloads.Citation;
import dev.cimiento.core.rules.RuleResult.Outcome;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The core engine against the independent reference (Python generator and networkx-checked
 * solver) on every tender of both splits: critical path, durations, term check, takeoff sums
 * and budget. Data comes from {@code make data}.
 */
class GroundTruthTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Citation> CITE = List.of(new Citation("mediciones", 1, "x"));
    private static Path dataDir;
    private static RuleEngine engine;

    @BeforeAll
    static void load() throws IOException {
        dataDir = Path.of(System.getProperty("cimiento.dataDir", "../../data/generated"));
        if (!Files.isDirectory(dataDir.resolve("dev/ground_truth")) || !Files.isDirectory(dataDir.resolve("eval/ground_truth"))) {
            fail("synthetic data not found in " + dataDir.toAbsolutePath() + "; run `make data`");
        }
        JsonNode tarifas = MAPPER.readTree(dataDir.resolve("dev/company/tarifas.json").toFile());
        Map<String, Rate> rates = new HashMap<>();
        for (JsonNode p : tarifas.get("partidas")) {
            rates.put(p.get("codigo").asText(), new Rate(p.get("codigo").asText(), p.get("actividad").asText(),
                    p.get("unidad").asText(), new BigDecimal(p.get("precio_unitario_eur").asText()),
                    new BigDecimal(p.get("rendimiento_por_dia").asText())));
        }
        engine = new RuleEngine(rates, new BigDecimal("1000000000"), MAPPER);
    }

    static Stream<Path> tenders() throws IOException {
        Path dir = Path.of(System.getProperty("cimiento.dataDir", "../../data/generated"));
        List<Path> all = new ArrayList<>();
        for (String split : List.of("dev", "eval")) {
            Path gt = dir.resolve(split).resolve("ground_truth");
            if (Files.isDirectory(gt)) {
                try (Stream<Path> s = Files.list(gt)) {
                    s.filter(p -> p.toString().endsWith(".json")).sorted().forEach(all::add);
                }
            }
        }
        return all.stream();
    }

    @Test
    void bothSplitsArePresent() throws IOException {
        long base = tenders().filter(p -> !p.getFileName().toString().contains("gemelo")).count();
        assertThat(base).isEqualTo(60);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenders")
    void criticalPathAndDurationMatchReference(Path file) throws IOException {
        JsonNode gt = MAPPER.readTree(file.toFile());
        JsonNode plan = gt.get("planning");
        List<Cpm.ActivityInput> acts = new ArrayList<>();
        plan.get("actividades").forEach(a -> acts.add(
                new Cpm.ActivityInput(a.get("id").asText(), a.get("nombre").asText(), a.get("duracion").asInt())));
        List<Cpm.Link> links = new ArrayList<>();
        plan.get("dependencias").forEach(d -> links.add(
                new Cpm.Link(d.get("de").asText(), d.get("a").asText(), d.get("desfase").asInt())));

        Cpm.Result r = Cpm.solve(acts, links);
        assertThat(r.duration()).isEqualTo(plan.get("duracion_dias_laborables").asInt());
        List<String> expectedCritical = new ArrayList<>();
        plan.get("actividades_criticas").forEach(c -> expectedCritical.add(c.asText()));
        assertThat(r.critical()).containsExactlyElementsOf(expectedCritical);
        for (JsonNode a : plan.get("actividades")) {
            var t = r.activities().get(a.get("id").asText());
            assertThat(List.of(t.es(), t.ef(), t.ls(), t.lf(), t.totalFloat()))
                    .as(a.get("id").asText())
                    .containsExactly(a.get("es").asInt(), a.get("ef").asInt(), a.get("ls").asInt(),
                            a.get("lf").asInt(), a.get("holgura_total").asInt());
            assertThat(t.critical()).isEqualTo(a.get("critica").asBoolean());
        }

        // The full rule engine on the same schedule: term check matches the reference flag.
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode pa = payload.putArray("activities");
        acts.forEach(a -> pa.addObject().put("code", a.code()).put("name", a.name()).put("duration", a.duration()));
        ArrayNode pl = payload.putArray("links");
        links.forEach(l -> pl.addObject().put("from", l.from()).put("to", l.to()).put("lag", l.lag()));
        var tender = new RuleEngine.TenderContext(LocalDate.parse(plan.get("fecha_inicio").asText()),
                plan.get("plazo_contractual_meses").asInt());
        var e = engine.evaluate(ProposalType.SCHEDULE, payload, CITE, List.of(), tender);
        assertThat(e.failed()).isFalse();
        RuleResult term = e.results().stream().filter(x -> x.rule().equals("CONTRACT_TERM")).findFirst().orElseThrow();
        boolean exceeds = plan.get("supera_plazo").asBoolean();
        assertThat(term.outcome()).isEqualTo(exceeds ? Outcome.WARN : Outcome.PASS);
        assertThat(term.details().get("end")).isEqualTo(plan.get("fecha_fin").asText());
        assertThat(term.details().get("deadline")).isEqualTo(plan.get("fecha_limite_contractual").asText());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenders")
    void takeoffAndBudgetMatchReference(Path file) throws IOException {
        JsonNode gt = MAPPER.readTree(file.toFile());
        ObjectNode takeoff = MAPPER.createObjectNode();
        ArrayNode items = takeoff.putArray("items");
        ObjectNode budget = MAPPER.createObjectNode();
        ArrayNode lines = budget.putArray("lines");
        for (JsonNode m : gt.get("mediciones")) {
            ObjectNode item = items.addObject();
            item.put("code", m.get("codigo").asText());
            item.put("unit", m.get("unidad").asText());
            item.put("quantity", new BigDecimal(m.get("cantidad").asText()));
            ArrayNode rows = item.putArray("rows");
            for (JsonNode f : m.get("filas")) {
                String[] cols = f.get("texto").asText().split("\\|");
                rows.addObject()
                        .put("unitText", cols[3].trim())
                        .put("quantity", SpanishNumbers.parse(cols[4].trim()))
                        .put("duplicate", f.get("duplicada").asBoolean());
            }
            lines.addObject().put("code", m.get("codigo").asText())
                    .put("quantity", new BigDecimal(m.get("cantidad").asText()));
        }
        budget.put("claimedTotal", new BigDecimal(gt.get("presupuesto").get("total").asText()));

        var tender = new RuleEngine.TenderContext(LocalDate.of(2027, 1, 4), 12);
        var t = engine.evaluate(ProposalType.TAKEOFF, takeoff, CITE, List.of(), tender);
        assertThat(t.results()).filteredOn(r -> r.outcome() == Outcome.FAIL).isEmpty();
        var b = engine.evaluate(ProposalType.BUDGET, budget, CITE, List.of(), tender);
        assertThat(b.results()).filteredOn(r -> r.outcome() == Outcome.FAIL).isEmpty();
        assertThat(b.budgetTotal()).isEqualByComparingTo(gt.get("presupuesto").get("total").asText());
    }
}
