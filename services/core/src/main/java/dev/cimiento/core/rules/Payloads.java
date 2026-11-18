package dev.cimiento.core.rules;

import java.math.BigDecimal;
import java.util.List;

/** Typed payloads per proposal type, as sent by agents (JSON). */
public final class Payloads {

    private Payloads() {
    }

    public record Citation(String document, Integer page, String text) {
    }

    /** {@code duplicate} is optional (absent means false). */
    public record TakeoffRow(String unitText, BigDecimal quantity, Boolean duplicate) {
        public boolean isDuplicate() {
            return Boolean.TRUE.equals(duplicate);
        }
    }

    public record TakeoffItem(String code, String unit, BigDecimal quantity, List<TakeoffRow> rows) {
    }

    public record Takeoff(List<TakeoffItem> items) {
    }

    public record BudgetLine(String code, BigDecimal quantity) {
    }

    public record Budget(List<BudgetLine> lines, BigDecimal claimedTotal) {
    }

    public record Schedule(List<Cpm.ActivityInput> activities, List<Cpm.Link> links) {
    }

    public record ProgressRow(String activity, int plannedPct, int actualPct) {
    }

    public record Progress(int week, List<ProgressRow> rows) {
    }

    public enum Severity { LOW, MEDIUM, HIGH }

    public record RiskSignal(String kind, Severity severity, String description) {
    }
}
