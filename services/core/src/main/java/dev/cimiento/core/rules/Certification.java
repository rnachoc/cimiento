package dev.cimiento.core.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Monthly certification of site work: what the executed work is worth. Pure rules, no Spring.
 *
 * <p>Each budget line belongs to an activity (the company rate says which); the weekly progress
 * says how much of each activity is done. The month is cut at the last progress week that closes
 * inside it. Per line: to date = amount x percent / 100; period = to date - previous (the to-date
 * of the last issued certification). Retention is taken on the period and VAT on what is left.
 * Everything is BigDecimal with 2 decimals and HALF_UP; a model never writes an amount.
 */
public final class Certification {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** A line of the approved budget: its work item, the activity it belongs to and its amount. */
    public record Line(String code, String activity, String description, BigDecimal amount) {
    }

    /** What an earlier issued certification left: its month and the to-date of each line. */
    public record Issued(YearMonth month, int number, Map<String, BigDecimal> toDateByLine) {
    }

    public record Params(BigDecimal retentionPct, BigDecimal vatPct) {
    }

    /**
     * @param weeks actual percent per activity in every progress week (week number starting at 1)
     */
    public record Input(YearMonth month, LocalDate start, List<Line> lines, Map<Integer, Map<String, Integer>> weeks,
                        List<Issued> issued, boolean budgetApproved, boolean progressApproved, Params params) {
    }

    public record LineResult(String code, String activity, String description, BigDecimal amount, int pct,
                             BigDecimal toDate, BigDecimal previous, BigDecimal period) {
    }

    public record Result(YearMonth month, YearMonth expectedMonth, Integer cutWeek, LocalDate cutDate,
                         List<LineResult> lines, BigDecimal budgetTotal, BigDecimal toDate, BigDecimal previous,
                         BigDecimal period, BigDecimal retention, BigDecimal net, BigDecimal vat, BigDecimal payable,
                         List<String> errors) {
        public boolean canIssue() {
            return errors.isEmpty();
        }
    }

    private Certification() {
    }

    /** First month to certify: the month in which the first progress week closes. */
    public static YearMonth firstMonth(LocalDate start) {
        return YearMonth.from(weekEnd(start, 1));
    }

    /** Last working day of progress week {@code week} (five working days per week). */
    public static LocalDate weekEnd(LocalDate start, int week) {
        return WorkCalendar.workday(start, 5 * week - 1);
    }

    public static Result evaluate(Input in) {
        List<String> errors = new ArrayList<>();
        if (!in.budgetApproved()) {
            errors.add("the budget is not approved yet");
        }
        if (!in.progressApproved()) {
            errors.add("the weekly progress is not approved yet");
        }
        YearMonth last = in.issued().stream().map(Issued::month).max(YearMonth::compareTo).orElse(null);
        YearMonth expected = last == null ? firstMonth(in.start()) : last.plusMonths(1);
        if (in.issued().stream().anyMatch(i -> i.month().equals(in.month()))) {
            errors.add("month " + in.month() + " is already certified");
        } else if (in.month().isBefore(expected)) {
            errors.add("month " + in.month() + " is before the next one to certify (" + expected + ")");
        } else if (in.month().isAfter(expected)) {
            errors.add("certify " + expected + " first: months are certified in order");
        }

        Integer cut = cutWeek(in);
        Map<String, BigDecimal> previous = in.issued().stream()
                .filter(i -> i.month().equals(last)).findFirst().map(Issued::toDateByLine).orElse(Map.of());
        List<LineResult> lines = new ArrayList<>();
        BigDecimal budget = BigDecimal.ZERO;
        BigDecimal toDate = BigDecimal.ZERO;
        BigDecimal prev = BigDecimal.ZERO;
        if (cut == null) {
            errors.add("no progress week closes in " + in.month());
        } else {
            Integer previousCut = null;
            if (last != null) {
                previousCut = cutWeek(in, last);
            }
            if (previousCut != null && cut <= previousCut) {
                errors.add("no new progress since the last certification (week " + previousCut + ")");
            }
            for (Line line : in.lines()) {
                int pct = percent(in.weeks(), line.activity(), cut);
                if (pct < 0 || pct > 100) {
                    errors.add(line.code() + ": progress of " + line.activity() + " is " + pct + " %, outside 0 to 100");
                }
                BigDecimal lineToDate = line.amount().multiply(BigDecimal.valueOf(pct)).divide(HUNDRED, 2,
                        RoundingMode.HALF_UP);
                BigDecimal lineBefore = previous.getOrDefault(line.code(), BigDecimal.ZERO);
                BigDecimal period = lineToDate.subtract(lineBefore);
                if (period.signum() < 0) {
                    errors.add(line.code() + ": progress went back, " + lineToDate.toPlainString()
                            + " to date is below the " + lineBefore.toPlainString() + " already certified");
                }
                lines.add(new LineResult(line.code(), line.activity(), line.description(), line.amount(), pct,
                        lineToDate, lineBefore, period));
                budget = budget.add(line.amount());
                toDate = toDate.add(lineToDate);
                prev = prev.add(lineBefore);
            }
        }
        BigDecimal period = toDate.subtract(prev);
        BigDecimal retention = period.multiply(in.params().retentionPct()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal net = period.subtract(retention);
        BigDecimal vat = net.multiply(in.params().vatPct()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return new Result(in.month(), expected, cut, cut == null ? null : weekEnd(in.start(), cut), List.copyOf(lines),
                budget, toDate, prev, period, retention, net, vat, net.add(vat), List.copyOf(errors));
    }

    /** Last progress week that closes inside {@code month}, or null when none does. */
    static Integer cutWeek(Input in) {
        return cutWeek(in, in.month());
    }

    private static Integer cutWeek(Input in, YearMonth month) {
        Integer cut = null;
        for (int week : new TreeMap<>(in.weeks()).keySet()) {
            if (YearMonth.from(weekEnd(in.start(), week)).equals(month)) {
                cut = week;
            }
        }
        return cut;
    }

    /** Latest known percent of an activity up to {@code cut}; 0 when it has no row yet. */
    static int percent(Map<Integer, Map<String, Integer>> weeks, String activity, int cut) {
        int found = 0;
        int foundWeek = -1;
        for (Map.Entry<Integer, Map<String, Integer>> e : weeks.entrySet()) {
            Integer p = e.getValue().get(activity);
            if (e.getKey() <= cut && e.getKey() > foundWeek && p != null) {
                found = p;
                foundWeek = e.getKey();
            }
        }
        return found;
    }
}
