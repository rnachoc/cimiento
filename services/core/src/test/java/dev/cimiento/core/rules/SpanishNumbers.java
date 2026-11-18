package dev.cimiento.core.rules;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Test helper: parses Spanish formatted numbers ("1.234,50", "1234,5", "1 234,50"). */
final class SpanishNumbers {

    private static final Pattern FORMAT = Pattern.compile("^\\d{1,3}(?:[. ]\\d{3})*(?:,\\d+)?$|^\\d+(?:,\\d+)?$");

    private SpanishNumbers() {
    }

    static BigDecimal parse(String text) {
        if (!FORMAT.matcher(text).matches()) {
            throw new IllegalArgumentException("not a Spanish number: " + text);
        }
        return new BigDecimal(text.replace(".", "").replace(" ", "").replace(",", "."));
    }
}
