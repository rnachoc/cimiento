package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SiteTest {

    // A01 (10) -> A02 (10) -> A04 (5); A01 -> A03 (5) with 10 days of float
    static final List<Cpm.ActivityInput> ACTS = List.of(new Cpm.ActivityInput("A01", "Tierras", 10),
            new Cpm.ActivityInput("A02", "Estructura", 10), new Cpm.ActivityInput("A03", "Saneamiento", 5),
            new Cpm.ActivityInput("A04", "Cubierta", 5));
    static final List<Cpm.Link> LINKS = List.of(new Cpm.Link("A01", "A02", 0), new Cpm.Link("A02", "A04", 0),
            new Cpm.Link("A01", "A03", 0));

    static Site.Row row(String a, int planned, int actual) {
        return new Site.Row(a, planned, actual);
    }

    @Test
    void curveIsWeightedByDurationAndMissingActivitiesCountAsZero() {
        var r = Site.analyse(ACTS, LINKS, Map.of(
                1, List.of(row("A01", 50, 50)),
                2, List.of(row("A01", 100, 100))));
        // total duration 30: week 1 = 10 x 50 / 30, week 2 = 10 x 100 / 30
        assertThat(r.curve()).extracting(Site.CurvePoint::week).containsExactly(1, 2);
        assertThat(r.curve().get(0).plannedPct()).isEqualByComparingTo("16.7");
        assertThat(r.curve().get(1).actualPct()).isEqualByComparingTo("33.3");
        assertThat(r.impactDays()).isZero();
    }

    @Test
    void lateActivityOnTheCriticalPathMovesTheEnd() {
        // week 3 = 15 working days: A02 started at day 10 and should be 50 % done; it is at 20 %
        var r = Site.analyse(ACTS, LINKS, Map.of(3, List.of(row("A01", 100, 100), row("A02", 50, 20),
                row("A03", 0, 0))));
        Site.Deviation a02 = r.deviations().stream().filter(d -> d.activity().equals("A02")).findFirst().orElseThrow();
        // elapsed 15 - 10 = 5 days, done 20 % of 10 = 2 days: 3 days late
        assertThat(a02.delayDays()).isEqualTo(3);
        assertThat(a02.delayIsLowerBound()).isFalse();
        assertThat(a02.critical()).isTrue();
        assertThat(a02.alarm()).isTrue();
        assertThat(r.impactDays()).isEqualTo(3);
        assertThat(r.impactIsLowerBound()).isFalse();
        assertThat(r.forecastDuration()).isEqualTo(r.plannedDuration() + 3);
        // activities with nothing planned and nothing done are left out
        assertThat(r.deviations()).extracting(Site.Deviation::activity).containsExactly("A01", "A02");
    }

    @Test
    void lateActivityWithinItsFloatRaisesNoAlarm() {
        // A03 may start at day 10 and has 10 days of float; at day 15 it has not started
        var r = Site.analyse(ACTS, LINKS, Map.of(3, List.of(row("A01", 100, 100), row("A02", 50, 50),
                row("A03", 100, 0))));
        Site.Deviation a03 = r.deviations().stream().filter(d -> d.activity().equals("A03")).findFirst().orElseThrow();
        assertThat(a03.delayDays()).isEqualTo(5);
        assertThat(a03.delayIsLowerBound()).isTrue();
        assertThat(a03.totalFloat()).isEqualTo(10);
        assertThat(a03.alarm()).isFalse();
        assertThat(r.impactDays()).isZero();
        assertThat(r.impactIsLowerBound()).isTrue();
    }

    @Test
    void aheadOfPlanIsNotLateAndAnyLateRowIsAtLeastOneDay() {
        var r = Site.analyse(ACTS, LINKS, Map.of(2, List.of(row("A01", 100, 100), row("A02", 1, 0))));
        assertThat(r.deviations().get(0).delayDays()).isZero();
        var tiny = Site.analyse(ACTS, LINKS, Map.of(3, List.of(row("A01", 100, 100), row("A02", 50, 49))));
        assertThat(tiny.deviations().get(1).delayDays()).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> Site.analyse(ACTS, LINKS, Map.of())).hasMessageContaining("no progress");
    }

    /**
     * The core against the generator's ground truth on every tender of both splits: same delays for
     * every activity already started, same alarms, same impact when no delay is a lower bound.
     */
    @Test
    void matchesTheGroundTruthOfEveryGeneratedSite() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Path data = Path.of(System.getProperty("cimiento.dataDir", "../../data/generated"));
        int sites = 0;
        int started = 0;
        int alarms = 0;
        int alarmsAgree = 0;
        int exactImpact = 0;
        int impactComparable = 0;
        for (String split : List.of("dev", "eval")) {
            List<Path> files;
            try (Stream<Path> s = Files.list(data.resolve(split).resolve("ground_truth"))) {
                files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            }
            for (Path f : files) {
                JsonNode gt = mapper.readTree(f.toFile());
                String id = gt.get("id").asText();
                Path csv = data.resolve(split).resolve("tenders").resolve(id).resolve("obra").resolve("avance_semanal.csv");
                if (!gt.has("obra") || !Files.exists(csv)) {
                    continue;
                }
                sites++;
                JsonNode plan = gt.get("planning");
                List<Cpm.ActivityInput> acts = new ArrayList<>();
                plan.get("actividades").forEach(a -> acts.add(new Cpm.ActivityInput(a.get("id").asText(),
                        a.get("nombre").asText(), a.get("duracion").asInt())));
                List<Cpm.Link> links = new ArrayList<>();
                plan.get("dependencias").forEach(d -> links.add(new Cpm.Link(d.get("de").asText(), d.get("a").asText(),
                        d.get("desfase").asInt())));
                Map<Integer, List<Site.Row>> weeks = new HashMap<>();
                for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8).subList(1,
                        Files.readAllLines(csv, StandardCharsets.UTF_8).size())) {
                    String[] c = line.split(";");
                    weeks.computeIfAbsent(Integer.parseInt(c[0]), k -> new ArrayList<>())
                            .add(new Site.Row(c[2], Integer.parseInt(c[4]), Integer.parseInt(c[5])));
                }
                Site.Result r = Site.analyse(acts, links, weeks);
                JsonNode obra = gt.get("obra");
                assertThat(r.statusWeek()).as(id).isEqualTo(obra.get("semana_corte").asInt());
                Map<String, Site.Deviation> mine = new HashMap<>();
                r.deviations().forEach(d -> mine.put(d.activity(), d));
                boolean anyLowerBound = false;
                for (JsonNode d : obra.get("desviaciones")) {
                    Site.Deviation m = mine.get(d.get("actividad").asText());
                    assertThat(m).as(id + " " + d.get("actividad").asText()).isNotNull();
                    alarms++;
                    if (m.alarm() == d.get("alarma").asBoolean()) {
                        alarmsAgree++;
                    }
                    anyLowerBound |= m.delayIsLowerBound();
                    if (!m.delayIsLowerBound()) {
                        started++;
                        assertThat(m.delayDays()).as(id + " " + m.activity()).isEqualTo(d.get("retraso_dias").asInt());
                        assertThat(m.alarm()).as(id + " " + m.activity()).isEqualTo(d.get("alarma").asBoolean());
                    }
                }
                if (!anyLowerBound) {
                    impactComparable++;
                    assertThat(r.impactDays()).as(id).isEqualTo(obra.get("impacto_fin_obra_dias").asInt());
                    exactImpact++;
                }
            }
        }
        assertThat(sites).isGreaterThanOrEqualTo(60);
        assertThat(started).isPositive();
        assertThat(impactComparable).isEqualTo(exactImpact);
        // alarms on activities not started yet use a lower bound of the delay; they may disagree
        assertThat(BigDecimal.valueOf(alarmsAgree).divide(BigDecimal.valueOf(alarms), 3, java.math.RoundingMode.DOWN))
                .isGreaterThanOrEqualTo(new BigDecimal("0.95"));
        System.out.printf("site vs ground truth: %d sites, %d started activities exact, alarms %d/%d, impact exact %d%n",
                sites, started, alarmsAgree, alarms, exactImpact);
    }
}
