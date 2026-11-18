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

    @Test
    void bc3HasRootBreakdownAndOneConceptPerLine() {
        var lines = Exports.price(List.of(new Payloads.BudgetLine("MT01", new BigDecimal("100.50")),
                new Payloads.BudgetLine("CI01", new BigDecimal("3"))), RATES, Map.of("MT01", "Excavación | vaciado"));
        String bc3 = Exports.bc3("E-001", "Presupuesto E-001", MONDAY, lines);
        String[] records = bc3.split("\r\n");
        assertThat(bc3).endsWith("\r\n");
        assertThat(bc3.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
        assertThat(records[0]).startsWith("~V|").contains("FIEBDC-3/2016").contains("|ANSI|");
        assertThat(records[1]).startsWith("~K|");
        assertThat(records[2]).isEqualTo("~C|E-001##||Presupuesto E-001|1535.48|04012027|0|");
        assertThat(records[3]).isEqualTo("~D|E-001##|MT01\\1\\100.5\\CI01\\1\\3\\|");
        // separators inside text are removed
        assertThat(records[4]).isEqualTo("~C|MT01|m3|Excavación   vaciado|12.35|04012027|0|");
        assertThat(records[5]).isEqualTo("~C|CI01|m3|A02|98.10|04012027|0|");
        assertThat(records).hasSize(6);
        // the Spanish text survives the ANSI code page
        assertThat(new String(bc3.getBytes(Exports.BC3_CHARSET), Exports.BC3_CHARSET)).contains("Excavación");
    }
}
