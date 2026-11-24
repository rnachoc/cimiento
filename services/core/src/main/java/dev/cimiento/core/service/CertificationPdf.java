package dev.cimiento.core.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Draws an issued certification as an A4 PDF from its frozen snapshot. It only formats: every amount
 * comes from the snapshot the core stored when the certification was issued, nothing is recalculated.
 */
public final class CertificationPdf {

    /** One budget line as the snapshot stored it. */
    public record Line(String code, String activity, String description, BigDecimal amount, int pct,
                       BigDecimal toDate, BigDecimal previous, BigDecimal period) {
    }

    /** Everything printed on the document. */
    public record Doc(String company, String tenderCode, String tenderTitle, int number, String month, int cutWeek,
                      String issuedBy, Instant issuedAt, List<Line> lines, BigDecimal budget, BigDecimal toDate,
                      BigDecimal previous, BigDecimal period, BigDecimal retention, BigDecimal net, BigDecimal vat,
                      BigDecimal payable) {
    }

    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final float MARGIN = 40;
    private static final float TOP = PDRectangle.A4.getHeight() - MARGIN;
    private static final float BOTTOM = 70;
    private static final float ROW = 13;
    /** Right edges of the amount columns: budget, percent, to date, previous, this period. */
    private static final float[] COLS = {318, 356, 431, 495, 555};
    private static final float DESC_WIDTH = 210;

    private CertificationPdf() {
    }

    /** 1.264,45 € (always two decimals and grouping, like the console). */
    public static String money(BigDecimal v) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(ES));
        f.setGroupingSize(3);
        f.setGroupingUsed(true);
        return f.format(v.setScale(2, RoundingMode.HALF_UP)) + " €";
    }

    /** 2027-05 as "Mayo de 2027". */
    public static String monthLabel(String month) {
        YearMonth ym = YearMonth.parse(month);
        String name = ym.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, ES);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " de " + ym.getYear();
    }

    /** A rate of b over a, for labels only ("5 %"); empty when a is zero. */
    static String pct(BigDecimal part, BigDecimal base) {
        if (base == null || base.signum() == 0) {
            return "";
        }
        BigDecimal p = part.multiply(BigDecimal.valueOf(100)).divide(base, 1, RoundingMode.HALF_UP).stripTrailingZeros();
        return " (" + p.toPlainString().replace('.', ',') + " %)";
    }

    private sealed interface Row permits Group, Item {
    }

    private record Group(String activity, int pct, int count, BigDecimal amount, BigDecimal toDate,
                         BigDecimal previous, BigDecimal period) implements Row {
    }

    private record Item(Line line) implements Row {
    }

    /** Lines grouped by activity, in the order they first appear; the group sums its lines. */
    static List<Row> rows(List<Line> lines) {
        Map<String, List<Line>> byActivity = new LinkedHashMap<>();
        for (Line l : lines) {
            byActivity.computeIfAbsent(l.activity(), k -> new ArrayList<>()).add(l);
        }
        List<Row> out = new ArrayList<>();
        byActivity.forEach((activity, ls) -> {
            out.add(new Group(activity, ls.get(0).pct(), ls.size(), sum(ls, Line::amount), sum(ls, Line::toDate),
                    sum(ls, Line::previous), sum(ls, Line::period)));
            ls.forEach(l -> out.add(new Item(l)));
        });
        return out;
    }

    private static BigDecimal sum(List<Line> ls, java.util.function.Function<Line, BigDecimal> f) {
        return ls.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static byte[] render(Doc d) {
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        List<Row> rows = rows(d.lines());
        // first page holds the heading; the totals need about 150 points after the last row
        List<List<Row>> pages = new ArrayList<>();
        float firstStart = TOP - 150, nextStart = TOP - 40;
        List<Row> current = new ArrayList<>();
        float y = firstStart;
        for (Row r : rows) {
            if (y - ROW < BOTTOM) {
                pages.add(current);
                current = new ArrayList<>();
                y = nextStart;
            }
            current.add(r);
            y -= ROW;
        }
        pages.add(current);
        boolean totalsOwnPage = y - 150 < BOTTOM;
        int pageCount = pages.size() + (totalsOwnPage ? 1 : 0);

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Certificación " + d.number() + " · " + d.tenderCode() + " · " + d.month());
            info.setProducer("Cimiento");
            float lastY = 0;
            for (int i = 0; i < pages.size(); i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float top;
                    if (i == 0) {
                        heading(cs, regular, bold, d);
                        top = firstStart;
                    } else {
                        text(cs, bold, 9, MARGIN, TOP - 10, "Certificación nº " + d.number() + " · " + d.tenderCode()
                                + " · " + monthLabel(d.month()) + " (continuación)");
                        top = nextStart;
                    }
                    columnHeads(cs, bold, top + 3);
                    lastY = table(cs, regular, bold, pages.get(i), top);
                    footer(cs, regular, d, i + 1, pageCount);
                    if (i == pages.size() - 1 && !totalsOwnPage) {
                        totals(cs, regular, bold, d, lastY - 18);
                    }
                }
            }
            if (totalsOwnPage) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    totals(cs, regular, bold, d, TOP - 40);
                    footer(cs, regular, d, pageCount, pageCount);
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void heading(PDPageContentStream cs, PDType1Font regular, PDType1Font bold, Doc d)
            throws IOException {
        text(cs, bold, 11, MARGIN, TOP - 8, d.company());
        text(cs, regular, 8, MARGIN, TOP - 20, "Empresa ficticia · datos sintéticos");
        right(cs, bold, 18, COLS[4], TOP - 12, "CERTIFICACIÓN Nº " + d.number());
        right(cs, regular, 10, COLS[4], TOP - 26, monthLabel(d.month()));
        line(cs, MARGIN, TOP - 36, COLS[4], 0.8f);
        text(cs, bold, 10, MARGIN, TOP - 54, "Obra: " + d.tenderCode());
        text(cs, regular, 10, MARGIN + 90, TOP - 54, fit(regular, 10, d.tenderTitle(), COLS[4] - MARGIN - 90));
        text(cs, regular, 9, MARGIN, TOP - 70, "Corte de avance: semana " + d.cutWeek());
        String when = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(d.issuedAt().atZone(MADRID));
        text(cs, regular, 9, MARGIN, TOP - 84, "Emitida por " + d.issuedBy() + " el " + when
                + " (hora de Madrid). Registrada y auditada en el núcleo.");
        text(cs, bold, 11, MARGIN, TOP - 112, "Relación valorada");
    }

    private static void columnHeads(PDPageContentStream cs, PDType1Font bold, float y) throws IOException {
        text(cs, bold, 8, MARGIN, y, "Actividad / partida");
        String[] heads = {"Presupuesto", "% origen", "A origen", "Anterior", "Este periodo"};
        for (int i = 0; i < heads.length; i++) {
            right(cs, bold, 8, COLS[i], y, heads[i]);
        }
        line(cs, MARGIN, y - 4, COLS[4], 0.5f);
    }

    private static float table(PDPageContentStream cs, PDType1Font regular, PDType1Font bold, List<Row> rows,
                               float y) throws IOException {
        for (Row r : rows) {
            if (r instanceof Group g) {
                y -= ROW;
                cs.setNonStrokingColor(0.94f, 0.94f, 0.94f);
                cs.addRect(MARGIN - 2, y - 3, COLS[4] - MARGIN + 4, ROW);
                cs.fill();
                cs.setNonStrokingColor(0f, 0f, 0f);
                text(cs, bold, 8, MARGIN, y, g.activity() + "  ·  " + g.count() + (g.count() == 1 ? " partida" : " partidas"));
                amounts(cs, bold, y, g.amount(), g.pct() + " %", g.toDate(), g.previous(), g.period());
            } else if (r instanceof Item it) {
                y -= ROW;
                Line l = it.line();
                text(cs, regular, 7.5f, MARGIN + 8, y, fit(regular, 7.5f, l.code() + "  " + l.description(), DESC_WIDTH));
                amounts(cs, regular, y, l.amount(), "", l.toDate(), l.previous(), l.period());
            }
        }
        line(cs, MARGIN, y - 5, COLS[4], 0.5f);
        return y - 5;
    }

    private static void amounts(PDPageContentStream cs, PDType1Font font, float y, BigDecimal amount, String pct,
                                BigDecimal toDate, BigDecimal previous, BigDecimal period) throws IOException {
        float size = 7.5f;
        right(cs, font, size, COLS[0], y, money(amount));
        right(cs, font, size, COLS[1], y, pct);
        right(cs, font, size, COLS[2], y, money(toDate));
        right(cs, font, size, COLS[3], y, money(previous));
        right(cs, font, size, COLS[4], y, money(period));
    }

    private static void totals(PDPageContentStream cs, PDType1Font regular, PDType1Font bold, Doc d, float y)
            throws IOException {
        float label = 330;
        Object[][] rows = {
                {"Presupuesto de la obra", d.budget(), false},
                {"Ejecutado a origen", d.toDate(), false},
                {"Certificado anterior", d.previous(), false},
                {"Importe de este periodo", d.period(), true},
                {"Retención" + pct(d.retention(), d.period()), d.retention().negate(), false},
                {"Líquido", d.net(), false},
                {"IVA" + pct(d.vat(), d.net()), d.vat(), false},
                {"TOTAL A FACTURAR", d.payable(), true},
        };
        for (Object[] r : rows) {
            boolean strong = (Boolean) r[2];
            PDType1Font f = strong ? bold : regular;
            float size = strong ? 10 : 9;
            if (r[0].equals("TOTAL A FACTURAR")) {
                line(cs, label, y + 12, COLS[4], 0.8f);
            }
            text(cs, f, size, label, y, (String) r[0]);
            right(cs, f, size, COLS[4], y, money((BigDecimal) r[1]));
            y -= 15;
        }
    }

    private static void footer(PDPageContentStream cs, PDType1Font regular, Doc d, int page, int pages)
            throws IOException {
        line(cs, MARGIN, 52, COLS[4], 0.4f);
        text(cs, regular, 7, MARGIN, 40, "Generado por Cimiento a partir de la certificación emitida, sin recalcular. "
                + "Retención e IVA son parámetros ficticios. Datos sintéticos.");
        right(cs, regular, 7, COLS[4], 40, "Página " + page + " de " + pages);
    }

    private static void line(PDPageContentStream cs, float x1, float y, float x2, float width) throws IOException {
        cs.setLineWidth(width);
        cs.moveTo(x1, y);
        cs.lineTo(x2, y);
        cs.stroke();
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(safe(font, s));
        cs.endText();
    }

    private static void right(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        String t = safe(font, s);
        text(cs, font, size, x - width(font, size, t), y, t);
    }

    private static float width(PDType1Font font, float size, String s) throws IOException {
        return font.getStringWidth(s) / 1000 * size;
    }

    /** Cuts the text with an ellipsis so it fits the width. */
    static String fit(PDType1Font font, float size, String s, float max) throws IOException {
        String t = safe(font, s);
        if (width(font, size, t) <= max) {
            return t;
        }
        while (!t.isEmpty() && width(font, size, t + "…") > max) {
            t = t.substring(0, t.length() - 1);
        }
        return t.stripTrailing() + "…";
    }

    /** The standard fonts only encode WinAnsi: anything else becomes '?'. */
    static String safe(PDType1Font font, String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            String c = new String(Character.toChars(cp));
            try {
                font.encode(c);
                b.append(c);
            } catch (IOException | IllegalArgumentException e) {
                b.append('?');
            }
        });
        return b.toString();
    }
}
