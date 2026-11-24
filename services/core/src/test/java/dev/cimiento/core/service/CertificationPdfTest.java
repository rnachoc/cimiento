package dev.cimiento.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class CertificationPdfTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static CertificationPdf.Doc doc(List<CertificationPdf.Line> lines) {
        return new CertificationPdf.Doc("Constructora Brisa", "E-001", "Reforma de un centro de salud", 2, "2027-05",
                18, "gerencia", Instant.parse("2027-06-01T08:30:00Z"), lines, d("176597.12"), d("52979.14"),
                d("40000.00"), d("12979.14"), d("648.96"), d("12330.18"), d("2589.34"), d("14919.52"));
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    void moneyAndMonthsAreSpanish() {
        assertThat(CertificationPdf.money(d("1264.45"))).isEqualTo("1.264,45 €");
        assertThat(CertificationPdf.money(d("1234567.8"))).isEqualTo("1.234.567,80 €");
        assertThat(CertificationPdf.money(d("-648.96"))).isEqualTo("-648,96 €");
        assertThat(CertificationPdf.money(d("0"))).isEqualTo("0,00 €");
        assertThat(CertificationPdf.monthLabel("2027-05")).isEqualTo("Mayo de 2027");
        assertThat(CertificationPdf.pct(d("648.96"), d("12979.14"))).isEqualTo(" (5 %)");
        assertThat(CertificationPdf.pct(d("1"), d("0"))).isEmpty();
    }

    @Test
    void printsTheSnapshotGroupedByActivityWithItsTotals() throws Exception {
        List<CertificationPdf.Line> lines = List.of(
                new CertificationPdf.Line("E02AM010", "A02", "Excavación a cielo abierto", d("1000.00"), 40,
                        d("400.00"), d("250.00"), d("150.00")),
                new CertificationPdf.Line("E02TT030", "A02", "Transporte de tierras", d("500.00"), 40,
                        d("200.00"), d("100.00"), d("100.00")),
                new CertificationPdf.Line("E04CM040", "A03", "Hormigón de limpieza", d("2000.00"), 10,
                        d("200.00"), d("0.00"), d("200.00")));
        byte[] pdf = CertificationPdf.render(doc(lines));
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String t = text(pdf);
        assertThat(t).contains("CERTIFICACIÓN Nº 2", "Mayo de 2027", "Constructora Brisa", "Obra: E-001",
                "Reforma de un centro de salud", "semana 18", "Emitida por gerencia el 01/06/2027 10:30");
        // the activity row adds up its lines; lines keep their own amounts
        assertThat(t).contains("A02  ·  2 partidas", "1.500,00 €", "250,00 €", "A03  ·  1 partida");
        assertThat(t).contains("E02AM010  Excavación a cielo abierto", "Hormigón de limpieza");
        assertThat(t).contains("Importe de este periodo", "12.979,14 €", "Retención (5 %)", "-648,96 €",
                "IVA (21 %)", "TOTAL A FACTURAR", "14.919,52 €", "Página 1 de 1");
    }

    @Test
    void longCertificationsRunOverSeveralPagesWithTheTotalsAtTheEnd() throws Exception {
        List<CertificationPdf.Line> lines = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            lines.add(new CertificationPdf.Line(String.format("P%04d", i), "A" + (i / 20), "Partida número " + i
                    + " con una descripción larga que no cabe en su columna y se recorta con puntos suspensivos",
                    d("100.00"), 50, d("50.00"), d("25.00"), d("25.00")));
        }
        byte[] pdf = CertificationPdf.render(doc(lines));
        int n = pages(pdf);
        assertThat(n).isGreaterThan(2);
        String t = text(pdf);
        assertThat(t).contains("Página 1 de " + n, "Página " + n + " de " + n, "(continuación)", "P0159");
        assertThat(t.indexOf("TOTAL A FACTURAR")).isGreaterThan(t.indexOf("P0159"));
        assertThat(t).contains("…");
    }

    @Test
    void charactersTheStandardFontCannotDrawBecomeQuestionMarks() {
        var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
        assertThat(CertificationPdf.safe(font, "Muro ≥ 30 cm · ñ €")).isEqualTo("Muro ? 30 cm · ñ €");
    }
}
