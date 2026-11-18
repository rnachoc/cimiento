package dev.cimiento.core.rules;

import java.math.BigDecimal;

/** Company rate for a work item: canonical unit, price per unit (VAT excluded), productivity. */
public record Rate(String code, String activity, String unit, BigDecimal unitPrice, BigDecimal ratePerDay) {
}
