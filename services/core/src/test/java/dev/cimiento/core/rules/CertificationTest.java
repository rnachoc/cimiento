package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CertificationTest {

    // Monday 2027-05-10: week 1 closes on Friday 14 May, week 4 on 4 June, week 8 on 2 July.
    private static final LocalDate START = LocalDate.of(2027, 5, 10);
    private static final Certification.Params PARAMS = new Certification.Params(new BigDecimal("5"),
            new BigDecimal("21"));
    private static final List<Certification.Line> LINES = List.of(
            new Certification.Line("MT01", "A01", "Excavación", new BigDecimal("1000.00")),
            new Certification.Line("MT02", "A01", "Relleno", new BigDecimal("500.00")),
            new Certification.Line("CI01", "A02", "Hormigón", new BigDecimal("2000.00")));

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static Map<Integer, Map<String, Integer>> weeks(Object... pairs) {
        Map<Integer, Map<String, Integer>> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 3) {
            m.computeIfAbsent((Integer) pairs[i], k -> new HashMap<>()).put((String) pairs[i + 1], (Integer) pairs[i + 2]);
        }
        return m;
    }

    private static Certification.Input input(String month, Map<Integer, Map<String, Integer>> weeks,
                                             List<Certification.Issued> issued) {
        return new Certification.Input(YearMonth.parse(month), START, LINES, weeks, issued, true, true, PARAMS);
    }

    @Test
    void firstMonthIsTheOneInWhichTheFirstWeekCloses() {
        assertThat(Certification.firstMonth(START)).isEqualTo(YearMonth.of(2027, 5));
        // week 1 of a Monday 31 May start closes on 4 June
        assertThat(Certification.firstMonth(LocalDate.of(2027, 5, 31))).isEqualTo(YearMonth.of(2027, 6));
    }

    @Test
    void firstCertificationValuesWhatIsDoneAtTheLastWeekOfTheMonth() {
        // May holds the weeks that close on 14, 21 and 28 May: cut at week 3
        var r = Certification.evaluate(input("2027-05", weeks(1, "A01", 40, 3, "A01", 60, 3, "A02", 10), List.of()));
        assertThat(r.errors()).isEmpty();
        assertThat(r.cutWeek()).isEqualTo(3);
        assertThat(r.cutDate()).isEqualTo(LocalDate.of(2027, 5, 28));
        // A01 at 60 %: 1000 x 0.60 + 500 x 0.60 = 900.00; A02 at 10 %: 200.00
        assertThat(r.lines()).extracting(Certification.LineResult::toDate)
                .containsExactly(d("600.00"), d("300.00"), d("200.00"));
        assertThat(r.toDate()).isEqualByComparingTo("1100.00");
        assertThat(r.previous()).isEqualByComparingTo("0");
        assertThat(r.period()).isEqualByComparingTo("1100.00");
        // retention 5 % = 55.00; net 1045.00; VAT 21 % = 219.45; payable 1264.45
        assertThat(r.retention()).isEqualByComparingTo("55.00");
        assertThat(r.net()).isEqualByComparingTo("1045.00");
        assertThat(r.vat()).isEqualByComparingTo("219.45");
        assertThat(r.payable()).isEqualByComparingTo("1264.45");
        assertThat(r.budgetTotal()).isEqualByComparingTo("3500.00");
    }

    @Test
    void anActivityWithoutARowKeepsItsLastKnownPercentAndBeforeAnyRowIsZero() {
        var w = weeks(1, "A01", 20, 2, "A02", 50, 3, "A01", 30);
        var r = Certification.evaluate(input("2027-05", w, List.of()));
        // at week 3 A01 is 30 % (row of week 3) and A02 keeps the 50 % of week 2
        assertThat(r.lines()).extracting(Certification.LineResult::pct).containsExactly(30, 30, 50);
        assertThat(Certification.percent(w, "A09", 3)).isZero();
    }

    @Test
    void thePeriodIsToDateMinusWhatTheLastCertificationLeft() {
        var may = Certification.evaluate(input("2027-05", weeks(3, "A01", 60, 3, "A02", 10), List.of()));
        var issued = List.of(new Certification.Issued(YearMonth.of(2027, 5), 1, Map.of(
                "MT01", may.lines().get(0).toDate(), "MT02", may.lines().get(1).toDate(),
                "CI01", may.lines().get(2).toDate())));
        // June holds the weeks that close on 4, 11, 18 and 25 June: weeks 4 to 7, cut at week 7
        var w = weeks(3, "A01", 60, 3, "A02", 10, 7, "A01", 100, 7, "A02", 35);
        var june = Certification.evaluate(input("2027-06", w, issued));
        assertThat(june.errors()).isEmpty();
        assertThat(june.cutWeek()).isEqualTo(7);
        assertThat(june.toDate()).isEqualByComparingTo("2200.00"); // 1500 + 700
        assertThat(june.previous()).isEqualByComparingTo("1100.00");
        assertThat(june.period()).isEqualByComparingTo("1100.00");
    }

    @Test
    void monthsAreCertifiedInOrderAndOnlyOnce() {
        var w = weeks(3, "A01", 60, 7, "A01", 100, 11, "A01", 100);
        var may = Certification.evaluate(input("2027-05", w, List.of()));
        var issued = List.of(new Certification.Issued(YearMonth.of(2027, 5), 1, Map.of("MT01", may.lines().get(0)
                .toDate(), "MT02", may.lines().get(1).toDate(), "CI01", BigDecimal.ZERO)));
        assertThat(Certification.evaluate(input("2027-05", w, issued)).errors())
                .anyMatch(e -> e.contains("already certified"));
        assertThat(Certification.evaluate(input("2027-07", w, issued)).errors())
                .anyMatch(e -> e.contains("certify 2027-06 first"));
        assertThat(Certification.evaluate(input("2027-04", w, List.of())).errors())
                .anyMatch(e -> e.contains("before the next one to certify"));
        assertThat(Certification.evaluate(input("2027-06", w, issued)).expectedMonth()).isEqualTo(YearMonth.of(2027, 6));
    }

    @Test
    void aMonthWithoutProgressOrWithoutNewProgressCannotBeIssued() {
        var none = Certification.evaluate(input("2027-05", weeks(9, "A01", 10), List.of()));
        assertThat(none.errors()).anyMatch(e -> e.contains("no progress week closes in 2027-05"));
        assertThat(none.cutWeek()).isNull();
        // the data ends in May, so June has nothing new
        var may = Certification.evaluate(input("2027-05", weeks(3, "A01", 60), List.of()));
        var issued = List.of(new Certification.Issued(YearMonth.of(2027, 5), 1, Map.of("MT01", may.lines().get(0)
                .toDate())));
        assertThat(Certification.evaluate(input("2027-06", weeks(3, "A01", 60), issued)).errors())
                .anyMatch(e -> e.contains("no progress week closes in 2027-06"));
    }

    @Test
    void progressThatGoesBackOrExceedsHundredBlocksTheCertification() {
        var may = Certification.evaluate(input("2027-05", weeks(3, "A01", 60), List.of()));
        var issued = List.of(new Certification.Issued(YearMonth.of(2027, 5), 1, Map.of("MT01", may.lines().get(0)
                .toDate(), "MT02", may.lines().get(1).toDate())));
        var back = Certification.evaluate(input("2027-06", weeks(3, "A01", 60, 7, "A01", 50), issued));
        assertThat(back.errors()).anyMatch(e -> e.startsWith("MT01: progress went back"));
        var over = Certification.evaluate(input("2027-05", weeks(3, "A01", 120), List.of()));
        assertThat(over.errors()).anyMatch(e -> e.contains("outside 0 to 100"));
    }

    @Test
    void anUnapprovedBudgetOrProgressBlocksIssuing() {
        var in = new Certification.Input(YearMonth.of(2027, 5), START, LINES, weeks(3, "A01", 60), List.of(), false,
                false, PARAMS);
        assertThat(Certification.evaluate(in).errors()).contains("the budget is not approved yet",
                "the weekly progress is not approved yet");
    }

    @Test
    void roundingIsHalfUpToTwoDecimals() {
        var odd = List.of(new Certification.Line("X", "A01", "x", new BigDecimal("333.33")));
        var in = new Certification.Input(YearMonth.of(2027, 5), START, odd, weeks(3, "A01", 50), List.of(), true,
                true, PARAMS);
        var r = Certification.evaluate(in);
        assertThat(r.toDate()).isEqualByComparingTo("166.67"); // 166.665 rounds up
        assertThat(r.retention()).isEqualByComparingTo("8.33"); // 8.3335
        assertThat(r.net()).isEqualByComparingTo("158.34");
    }

    /**
     * The core against the generator's data: for every tender of both splits that has a budget and a
     * weekly progress file, the to-date at the last week equals the amount worked out independently
     * here from the ground truth budget and the CSV, to the cent; and the periods of all months add
     * up to that to-date.
     */
    @Test
    void certifiesEveryGeneratedSiteToTheCent() throws Exception {
        Path data = Path.of("../../data/generated");
        if (!Files.exists(data.resolve("dev/manifest.json")) || !Files.exists(data.resolve("eval/manifest.json"))) {
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        int sites = 0;
        int compared = 0;
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
                Map<String, String> activityOf = new HashMap<>();
                gt.get("planning").get("actividades").forEach(a -> a.get("partidas")
                        .forEach(p -> activityOf.put(p.asText(), a.get("id").asText())));
                List<Certification.Line> lines = new ArrayList<>();
                gt.get("presupuesto").get("lineas").forEach(l -> lines.add(new Certification.Line(l.get("codigo").asText(),
                        activityOf.get(l.get("codigo").asText()), "", new BigDecimal(l.get("importe").asText()))));
                Map<Integer, Map<String, Integer>> weeks = new HashMap<>();
                List<String> csvLines = Files.readAllLines(csv, StandardCharsets.UTF_8);
                for (String line : csvLines.subList(1, csvLines.size())) {
                    String[] c = line.split(";");
                    weeks.computeIfAbsent(Integer.parseInt(c[0]), k -> new HashMap<>()).put(c[2], Integer.parseInt(c[5]));
                }
                LocalDate start = LocalDate.parse(gt.get("planning").get("fecha_inicio").asText());
                int last = weeks.keySet().stream().max(Integer::compare).orElseThrow();

                // independent expectation at the last week: every line at its activity's last percent
                BigDecimal expected = BigDecimal.ZERO;
                for (Certification.Line l : lines) {
                    int pct = weeks.get(last).getOrDefault(l.activity(), 0);
                    expected = expected.add(l.amount().multiply(BigDecimal.valueOf(pct))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                }
                // certify every month in order and add up the periods
                List<Certification.Issued> issued = new ArrayList<>();
                BigDecimal periods = BigDecimal.ZERO;
                YearMonth month = Certification.firstMonth(start);
                BigDecimal lastToDate = BigDecimal.ZERO;
                YearMonth lastMonth = YearMonth.from(Certification.weekEnd(start, last));
                while (!month.isAfter(lastMonth)) {
                    var r = Certification.evaluate(new Certification.Input(month, start, lines, weeks, issued, true,
                            true, PARAMS));
                    if (r.cutWeek() == null || r.errors().stream().anyMatch(e -> e.contains("no new progress"))) {
                        break;
                    }
                    assertThat(r.errors()).as(id + " " + month).isEmpty();
                    periods = periods.add(r.period());
                    lastToDate = r.toDate();
                    Map<String, BigDecimal> byLine = new HashMap<>();
                    r.lines().forEach(l -> byLine.put(l.code(), l.toDate()));
                    issued.add(new Certification.Issued(month, issued.size() + 1, byLine));
                    month = month.plusMonths(1);
                }
                assertThat(periods).as(id + " periods add up").isEqualByComparingTo(lastToDate);
                // the last certified month cuts at the last week only when that week closes it
                var all = Certification.evaluate(new Certification.Input(lastMonth, start, lines, weeks, List.of(), true,
                        true, PARAMS));
                if (all.cutWeek() != null && all.cutWeek() == last) {
                    assertThat(all.toDate()).as(id + " to date at week " + last).isEqualByComparingTo(expected);
                    compared++;
                }
            }
        }
        assertThat(sites).as("sites with progress").isGreaterThan(50);
        assertThat(compared).as("sites compared to the cent").isGreaterThan(30);
    }
}
