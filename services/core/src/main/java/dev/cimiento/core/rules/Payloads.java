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

    /** One earlier week of the weekly progress file. */
    public record ProgressWeek(int week, List<ProgressRow> rows) {
    }

    /** {@code history} is optional: earlier weeks, so the core can draw the progress curve. */
    public record Progress(int week, List<ProgressRow> rows, List<ProgressWeek> history) {
        public List<ProgressWeek> historyOrEmpty() {
            return history == null ? List.of() : history;
        }
    }

    public enum Severity { LOW, MEDIUM, HIGH }

    public record RiskSignal(String kind, Severity severity, String description) {
    }
}
