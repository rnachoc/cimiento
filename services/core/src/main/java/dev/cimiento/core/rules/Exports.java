package dev.cimiento.core.rules;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * File exports of validated proposals, computed by code from the same rules the core applies:
 * a schedule as Microsoft Project XML (MSPDI) and a budget as FIEBDC-3 (BC3). No I/O here.
 */
public final class Exports {

    /** BC3 files are written in the Windows Latin-1 code page ("ANSI" in the ~V record). */
    public static final Charset BC3_CHARSET = Charset.forName("windows-1252");

    private static final String MSPDI_NS = "http://schemas.microsoft.com/project";
    /** MSPDI durations and lags are in tenths of a minute; one working day is 8 hours. */
    private static final int TENTHS_PER_DAY = 8 * 60 * 10;
    private static final DateTimeFormatter BC3_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");

    private Exports() {
    }

    /**
     * Schedule as an MSPDI document: one task per activity with the dates of the core's CPM on a
     * Monday to Friday calendar, finish-to-start links with their lag, critical flag and total float.
     */
    public static String mspdi(String title, LocalDate start, Payloads.Schedule schedule) {
        Cpm.Result cpm = Cpm.solve(schedule.activities(), schedule.links());
        Map<String, Integer> uid = new HashMap<>();
        for (int i = 0; i < schedule.activities().size(); i++) {
            uid.put(schedule.activities().get(i).code(), i + 1);
        }
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element project = doc.createElementNS(MSPDI_NS, "Project");
            doc.appendChild(project);
            text(doc, project, "Name", title);
            text(doc, project, "StartDate", start + "T08:00:00");
            text(doc, project, "FinishDate", WorkCalendar.workday(start, Math.max(cpm.duration() - 1, 0)) + "T17:00:00");
            text(doc, project, "MinutesPerDay", "480");
            text(doc, project, "MinutesPerWeek", "2400");
            text(doc, project, "DaysPerMonth", "20");
            Element tasks = doc.createElementNS(MSPDI_NS, "Tasks");
            project.appendChild(tasks);
            for (Cpm.ActivityInput a : schedule.activities()) {
                Cpm.ActivityTimes times = cpm.activities().get(a.code());
                Element task = doc.createElementNS(MSPDI_NS, "Task");
                tasks.appendChild(task);
                text(doc, task, "UID", String.valueOf(uid.get(a.code())));
                text(doc, task, "ID", String.valueOf(uid.get(a.code())));
                text(doc, task, "Name", a.code() + " " + a.name());
                text(doc, task, "WBS", a.code());
                text(doc, task, "Start", WorkCalendar.workday(start, times.es()) + "T08:00:00");
                text(doc, task, "Finish", WorkCalendar.workday(start, times.ef() - 1) + "T17:00:00");
                text(doc, task, "Duration", "PT" + (a.duration() * 8) + "H0M0S");
                text(doc, task, "DurationFormat", "7");
                text(doc, task, "Critical", times.critical() ? "1" : "0");
                text(doc, task, "TotalSlack", String.valueOf(times.totalFloat() * TENTHS_PER_DAY));
                for (Cpm.Link l : schedule.links()) {
                    if (l.to().equals(a.code())) {
                        Element link = doc.createElementNS(MSPDI_NS, "PredecessorLink");
                        task.appendChild(link);
                        text(doc, link, "PredecessorUID", String.valueOf(uid.get(l.from())));
                        text(doc, link, "Type", "1");
                        text(doc, link, "LinkLag", String.valueOf(l.lag() * TENTHS_PER_DAY));
                        text(doc, link, "LagFormat", "7");
                    }
                }
            }
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("could not build MSPDI document", e);
        }
    }

    /** A budget line priced with the company's rate: amount = quantity x price, cents half up. */
    public record PricedLine(String code, String unit, String description, BigDecimal quantity, BigDecimal unitPrice,
                             BigDecimal amount) {
    }

    /** Prices every line with the company rates; unknown codes are an error, as in the BUDGET rule. */
    public static List<PricedLine> price(List<Payloads.BudgetLine> lines, Map<String, Rate> rates,
                                         Map<String, String> descriptions) {
        List<PricedLine> priced = new ArrayList<>();
        for (Payloads.BudgetLine l : lines) {
            Rate r = rates.get(l.code());
            if (r == null) {
                throw new IllegalArgumentException("no company rate for " + l.code());
            }
            BigDecimal amount = l.quantity().multiply(r.unitPrice()).setScale(2, RoundingMode.HALF_UP);
            priced.add(new PricedLine(l.code(), r.unit(), descriptions.getOrDefault(l.code(), r.activity()),
                    l.quantity(), r.unitPrice(), amount));
        }
        return priced;
    }

    /** Root concept (the work) and the chapter that holds every work item of the exported budget. */
    public static final String BC3_ROOT = "OBRA##";
    public static final String BC3_CHAPTER = "C01#";

    /**
     * Budget as a FIEBDC-3/2016 (BC3) file, following the format's own rules:
     * <ul>
     *   <li>~V: issuer, version with date, Windows character set (ANSI), information type 2 (budget);</li>
     *   <li>~K: the format's default decimals (field 1 kept for older readers, field 3 complete), EUR;</li>
     *   <li>a root concept (OBRA##, the tender code in its summary) that holds one chapter (#), which
     *       holds one work item per priced line;</li>
     *   <li>quantities and prices with exactly the decimals declared in ~K (2), so the file total is the
     *       sum of quantity x price as the BUDGET rule computes it;</li>
     *   <li>records end with CR LF and the file with ASCII 26.</li>
     * </ul>
     * A line whose quantity needs more than 2 decimals cannot be written exactly and is refused.
     */
    public static String bc3(String tenderCode, String title, LocalDate date, List<PricedLine> lines) {
        String root = BC3_ROOT;
        String day = date.format(BC3_DATE);
        BigDecimal total = lines.stream().map(PricedLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.UNNECESSARY);
        StringBuilder sb = new StringBuilder();
        record(sb, "~V|Cimiento|FIEBDC-3/2016\\" + day + "|Cimiento||ANSI|" + summary(title) + "|2|");
        record(sb, "~K|2\\2\\2\\3\\2\\2\\2\\2\\EUR\\||3\\2\\\\3\\3\\\\2\\2\\2\\2\\2\\2\\2\\2\\EUR\\|");
        record(sb, "~C|" + root + "||" + summary(clean(tenderCode) + " " + title) + "|" + total.toPlainString() + "|" + day
                + "|0|");
        record(sb, "~D|" + root + "|" + BC3_CHAPTER + "\\\\\\|");
        record(sb, "~C|" + BC3_CHAPTER + "||Presupuesto de ejecución material|" + total.toPlainString() + "|" + day + "|0|");
        StringBuilder breakdown = new StringBuilder();
        for (PricedLine l : lines) {
            breakdown.append(code(l.code())).append("\\\\").append(twoDecimals(l.quantity(), l.code())).append('\\');
        }
        record(sb, "~D|" + BC3_CHAPTER + "|" + breakdown + "|");
        for (PricedLine l : lines) {
            record(sb, "~C|" + code(l.code()) + "|" + clean(l.unit()) + "|" + summary(l.description()) + "|"
                    + twoDecimals(l.unitPrice(), l.code()) + "|" + day + "|0|");
        }
        return sb.append('\u001A').toString();
    }

    private static void record(StringBuilder sb, String record) {
        sb.append(record).append("\r\n");
    }

    private static String twoDecimals(BigDecimal value, String code) {
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(code + ": " + value.toPlainString() + " has more than 2 decimals");
        }
    }

    /** Codes are at most 20 characters and cannot contain the format's separators. */
    private static String code(String s) {
        String c = clean(s).replace(" ", "");
        if (c.isEmpty() || c.length() > 18) {
            throw new IllegalArgumentException("code '" + s + "' does not fit a BC3 code");
        }
        return c;
    }

    /** The format recommends summaries of at most 64 characters. */
    private static String summary(String s) {
        String c = clean(s);
        return c.length() <= 64 ? c : c.substring(0, 64).strip();
    }

    /** BC3 uses |, \ and ~ as separators and is single-line per record. */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("[|\\\\~\\r\\n\\u001A]", " ").strip();
    }

    private static void text(Document doc, Element parent, String name, String value) {
        Element e = doc.createElementNS(MSPDI_NS, name);
        e.setTextContent(value);
        parent.appendChild(e);
    }
}
