package dev.cimiento.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The rates seeded by Flyway must equal the generator's company rates. */
class RatesMatchGeneratorTest {

    private static final Pattern ROW = Pattern.compile(
            "\\('(\\w+)',\\s*'([^']*)',\\s*'(\\w+)',\\s*'(\\w+)',\\s*([\\d.]+),\\s*([\\d.]+)\\)");

    @Test
    void seededRatesEqualGeneratorRates() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V2__rates.sql"), StandardCharsets.UTF_8);
        Map<String, List<String>> seeded = new HashMap<>();
        Matcher m = ROW.matcher(sql);
        while (m.find()) {
            seeded.put(m.group(1), List.of(m.group(2), m.group(3), m.group(4),
                    new BigDecimal(m.group(5)).stripTrailingZeros().toPlainString(),
                    new BigDecimal(m.group(6)).stripTrailingZeros().toPlainString()));
        }
        Path dir = Path.of(System.getProperty("cimiento.dataDir", "../../data/generated"));
        JsonNode tarifas = new ObjectMapper().readTree(dir.resolve("dev/company/tarifas.json").toFile());
        Map<String, List<String>> generated = new HashMap<>();
        for (JsonNode p : tarifas.get("partidas")) {
            generated.put(p.get("codigo").asText(), List.of(p.get("descripcion").asText(), p.get("actividad").asText(),
                    p.get("unidad").asText(),
                    new BigDecimal(p.get("precio_unitario_eur").asText()).stripTrailingZeros().toPlainString(),
                    new BigDecimal(p.get("rendimiento_por_dia").asText()).stripTrailingZeros().toPlainString()));
        }
        assertThat(seeded).hasSize(15).isEqualTo(generated);
    }
}
