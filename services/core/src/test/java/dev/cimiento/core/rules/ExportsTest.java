package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class ExportsTest {

    static final LocalDate MONDAY = LocalDate.of(2027, 1, 4);

    static final Payloads.Schedule SCHEDULE = new Payloads.Schedule(
            List.of(new Cpm.ActivityInput("A01", "Tierras", 5), new Cpm.ActivityInput("A02", "Cimentación", 10),
                    new Cpm.ActivityInput("A03", "Estructura", 3)),
            List.of(new Cpm.Link("A01", "A02", 0), new Cpm.Link("A01", "A03", 2)));

    private static Document parse(String xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String child(Element e, String name) {
        return e.getElementsByTagNameNS("*", name).item(0).getTextContent();
    }

    @Test
    void mspdiCarriesTheCoreScheduleOnAWorkingDayCalendar() throws Exception {
        Document doc = parse(Exports.mspdi("Planning T-1", MONDAY, SCHEDULE));
        Element root = doc.getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("Project");
        assertThat(root.getNamespaceURI()).isEqualTo("http://schemas.microsoft.com/project");
        assertThat(child(root, "StartDate")).isEqualTo("2027-01-04T08:00:00");
        // 15 working days: A01 (5) then A02 (10)
        assertThat(child(root, "FinishDate")).isEqualTo("2027-01-22T17:00:00");

        NodeList tasks = doc.getElementsByTagNameNS("*", "Task");
        assertThat(tasks.getLength()).isEqualTo(3);
        Element a02 = (Element) tasks.item(1);
        assertThat(child(a02, "Name")).isEqualTo("A02 Cimentación");
        assertThat(child(a02, "Start")).isEqualTo("2027-01-11T08:00:00");
        assertThat(child(a02, "Finish")).isEqualTo("2027-01-22T17:00:00");
        assertThat(child(a02, "Duration")).isEqualTo("PT80H0M0S");
        assertThat(child(a02, "Critical")).isEqualTo("1");
        assertThat(child(a02, "PredecessorUID")).isEqualTo("1");

        Element a03 = (Element) tasks.item(2);
        // starts after A01 plus 2 days of lag, ends 3 days later, with float until day 15
        assertThat(child(a03, "Start")).isEqualTo("2027-01-13T08:00:00");
        assertThat(child(a03, "Finish")).isEqualTo("2027-01-15T17:00:00");
        assertThat(child(a03, "Critical")).isEqualTo("0");
        assertThat(child(a03, "TotalSlack")).isEqualTo(String.valueOf(5 * 4800));
        assertThat(child(a03, "LinkLag")).isEqualTo(String.valueOf(2 * 4800));
    }

    @Test
    void mspdiEscapesText() throws Exception {
        var s = new Payloads.Schedule(List.of(new Cpm.ActivityInput("A01", "Muros <norte> & sur", 1)), List.of());
        Document doc = parse(Exports.mspdi("Planning \"T&1\"", MONDAY, s));
        assertThat(child((Element) doc.getElementsByTagNameNS("*", "Task").item(0), "Name"))
                .isEqualTo("A01 Muros <norte> & sur");
    }

    static final Map<String, Rate> RATES = Map.of(
            "MT01", new Rate("MT01", "A01", "m3", new BigDecimal("12.35"), BigDecimal.TEN),
            "CI01", new Rate("CI01", "A02", "m3", new BigDecimal("98.10"), BigDecimal.TEN));

    @Test
    void pricingMatchesTheBudgetRuleAndRejectsUnknownCodes() {
        var lines = Exports.price(List.of(new Payloads.BudgetLine("MT01", new BigDecimal("100.5")),
                new Payloads.BudgetLine("CI01", new BigDecimal("3"))), RATES, Map.of("MT01", "Excavación | vaciado"));
        assertThat(lines.get(0).amount()).isEqualByComparingTo("1241.18"); // 1241.175 half up
        assertThat(lines.get(1).amount()).isEqualByComparingTo("294.30");
        assertThat(lines.get(1).description()).isEqualTo("A02"); // falls back to the activity
        assertThatThrownBy(() -> Exports.price(List.of(new Payloads.BudgetLine("ZZ99", BigDecimal.ONE)), RATES, Map.of()))
                .hasMessageContaining("ZZ99");
    }

    static List<Exports.PricedLine> twoLines() {
        return Exports.price(List.of(new Payloads.BudgetLine("MT01", new BigDecimal("100.50")),
                new Payloads.BudgetLine("CI01", new BigDecimal("3"))), RATES, Map.of("MT01", "Excavación | vaciado"));
    }

    /** Record layouts as written in the FIEBDC-3/2016 specification. */
    @Test
    void bc3FollowsTheFiebdc3Layout() {
        String bc3 = Exports.bc3("E-001", "Presupuesto E-001", MONDAY, twoLines());
        // records end with CR LF, the file with ASCII 26, and nothing else breaks a line
        assertThat(bc3).endsWith("\r\n\u001A");
        String body = bc3.substring(0, bc3.length() - 1);
        assertThat(body.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
        assertThat(body.split("\r\n")).containsExactly(
                "~V|Cimiento|FIEBDC-3/2016\\04012027|Cimiento||ANSI|Presupuesto E-001|2|",
                // DN DD DS DR DI DP DC DM DIVISA (defaults), empty CI GG BI BAJA IVA, then the complete field
                "~K|2\\2\\2\\3\\2\\2\\2\\2\\EUR\\||3\\2\\\\3\\3\\\\2\\2\\2\\2\\2\\2\\2\\2\\EUR\\|",
                "~C|OBRA##||E-001 Presupuesto E-001|1535.48|04012027|0|",
                "~D|OBRA##|C01#\\\\\\|",
                "~C|C01#||Presupuesto de ejecución material|1535.48|04012027|0|",
                "~D|C01#|MT01\\\\100.50\\CI01\\\\3.00\\|",
                // separators inside text are removed
                "~C|MT01|m3|Excavación   vaciado|12.35|04012027|0|",
                "~C|CI01|m3|A02|98.10|04012027|0|");
        // the Spanish text survives the ANSI code page
        assertThat(new String(bc3.getBytes(Exports.BC3_CHARSET), Exports.BC3_CHARSET)).contains("ejecución");
    }

    @Test
    void bc3FirstKFieldHasEightDecimalCountsAndTheCurrency() {
        String k = Exports.bc3("E-001", "x", MONDAY, twoLines()).lines().filter(l -> l.startsWith("~K|")).findFirst()
                .orElseThrow();
        String[] fields = k.split("\\|", -1);
        String[] first = fields[1].split("\\\\", -1);
        assertThat(first).hasSize(10); // 8 counts, EUR and the empty end after the final backslash
        assertThat(first[8]).isEqualTo("EUR");
        assertThat(fields[3].split("\\\\", -1)).hasSize(16); // 13 counts with 2 empty slots, EUR, end
    }

    /** What a reader checks: the declared prices equal the sum of quantity x price of the breakdown. */
    @Test
    void bc3TotalsAreConsistentForAReader() {
        String bc3 = Exports.bc3("E-001", "x", MONDAY, twoLines());
        Map<String, BigDecimal> price = new java.util.HashMap<>();
        Map<String, String> breakdown = new java.util.HashMap<>();
        for (String r : bc3.split("\r\n")) {
            String[] f = r.split("\\|", -1);
            if (f[0].equals("~C")) {
                price.put(f[1], new BigDecimal(f[4]));
            } else if (f[0].equals("~D")) {
                breakdown.put(f[1], f[2]);
            }
        }
        String[] parts = breakdown.get("C01#").split("\\\\", -1);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i + 2 < parts.length; i += 3) {
            // each line amount has DC = 2 decimals, as declared in ~K
            sum = sum.add(new BigDecimal(parts[i + 2]).multiply(price.get(parts[i]))
                    .setScale(2, java.math.RoundingMode.HALF_UP));
        }
        assertThat(sum).isEqualByComparingTo(price.get("C01#")).isEqualByComparingTo(price.get("OBRA##"))
                .isEqualByComparingTo("1535.48");
    }

    @Test
    void bc3RefusesWhatItCannotWriteExactly() {
        var tooFine = Exports.price(List.of(new Payloads.BudgetLine("MT01", new BigDecimal("1.255"))), RATES, Map.of());
        assertThatThrownBy(() -> Exports.bc3("E-001", "x", MONDAY, tooFine)).hasMessageContaining("more than 2 decimals");
        var longCode = List.of(new Exports.PricedLine("X".repeat(25), "m3", "x", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE));
        assertThatThrownBy(() -> Exports.bc3("E-001", "x", MONDAY, longCode)).hasMessageContaining("BC3 code");
        // a long tender code is fine: it only goes into the root's summary
        assertThat(Exports.bc3("T-" + "0".repeat(36), "x", MONDAY, twoLines())).contains("~C|OBRA##|");
        String longTitle = "Presupuesto ".repeat(10);
        assertThat(Exports.bc3("E-001", longTitle, MONDAY, twoLines()).split("\r\n")[2].split("\\|")[3]).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void mspdiSkipsHolidaysAndListsThemAsNonWorkingExceptions() throws Exception {
        var national = new WorkCalendar(WorkCalendar.Kind.ES_NATIONAL, Map.of());
        Document doc = parse(Exports.mspdi("Planning T-1", MONDAY, SCHEDULE, national));
        // 15 working days from Monday 4 January 2027 with Epiphany (Wednesday 6) end on Monday 25
        assertThat(child(doc.getDocumentElement(), "FinishDate")).isEqualTo("2027-01-25T17:00:00");
        NodeList ex = doc.getElementsByTagNameNS("*", "Exception");
        assertThat(ex.getLength()).isEqualTo(1);
        assertThat(child((Element) ex.item(0), "Name")).isEqualTo("Epifanía del Señor");
        assertThat(child((Element) ex.item(0), "DayWorking")).isEqualTo("0");
        // without holidays nothing changes
        Document plain = parse(Exports.mspdi("Planning T-1", MONDAY, SCHEDULE));
        assertThat(plain.getElementsByTagNameNS("*", "Calendars").getLength()).isZero();
        assertThat(child(plain.getDocumentElement(), "FinishDate")).isEqualTo("2027-01-22T17:00:00");
    }
}
