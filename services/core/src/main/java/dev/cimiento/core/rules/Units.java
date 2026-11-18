package dev.cimiento.core.rules;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** Unit spellings found in measurement tables and their canonical unit. */
public final class Units {

    public record Conversion(String canonical, BigDecimal factor) {
    }

    private static final Map<String, Conversion> SPELLINGS = Map.ofEntries(
            Map.entry("m3", new Conversion("m3", BigDecimal.ONE)),
            Map.entry("m³", new Conversion("m3", BigDecimal.ONE)),
            Map.entry("M3", new Conversion("m3", BigDecimal.ONE)),
            Map.entry("m2", new Conversion("m2", BigDecimal.ONE)),
            Map.entry("m²", new Conversion("m2", BigDecimal.ONE)),
            Map.entry("M2", new Conversion("m2", BigDecimal.ONE)),
            Map.entry("ha", new Conversion("m2", new BigDecimal("10000"))),
            Map.entry("m", new Conversion("m", BigDecimal.ONE)),
            Map.entry("ml", new Conversion("m", BigDecimal.ONE)),
            Map.entry("m.l.", new Conversion("m", BigDecimal.ONE)),
            Map.entry("km", new Conversion("m", new BigDecimal("1000"))),
            Map.entry("ud", new Conversion("ud", BigDecimal.ONE)),
            Map.entry("u", new Conversion("ud", BigDecimal.ONE)),
            Map.entry("uds", new Conversion("ud", BigDecimal.ONE)));

    private Units() {
    }

    public static Optional<Conversion> lookup(String spelling) {
        return Optional.ofNullable(spelling == null ? null : SPELLINGS.get(spelling.trim()));
    }
}
