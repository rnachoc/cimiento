package dev.cimiento.core.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Site progress against the schedule, computed by code (definitions in docs/SYNTHETIC_DATA.md):
 * the progress curve weighted by activity duration, the delay of each activity at the cut date,
 * the alarm when the delay exceeds the total float and the impact on the project end.
 *
 * <p>Week {@code w} ends after {@code 5 w} working days. An activity behind plan with real progress
 * is late by {@code elapsed working days - actual% x duration}, at least one day; one that has not
 * started yet is late at least since its early start (a lower bound). The impact is the CPM
 * duration with every delay added to its activity, minus the planned duration.
 */
public final class Site {

    public record Row(String activity, int plannedPct, int actualPct) {
    }

    public record CurvePoint(int week, BigDecimal plannedPct, BigDecimal actualPct) {
    }

    public record Deviation(String activity, String name, int plannedPct, int actualPct, int delayDays,
                            boolean delayIsLowerBound, int totalFloat, boolean critical, boolean alarm) {
    }

    /** {@code impactIsLowerBound}: some delay is a lower bound, so the impact is at least this much. */
    public record Result(int statusWeek, List<CurvePoint> curve, List<Deviation> deviations, int plannedDuration,
                         int forecastDuration, int impactDays, boolean impactIsLowerBound) {
    }

    private Site() {
    }

    /** {@code weeks}: rows per week number; activities missing from a week count as 0 %. */
    public static Result analyse(List<Cpm.ActivityInput> activities, List<Cpm.Link> links, Map<Integer, List<Row>> weeks) {
        if (weeks.isEmpty()) {
            throw new IllegalArgumentException("no progress weeks");
        }
        Cpm.Result plan = Cpm.solve(activities, links);
        Map<String, Cpm.ActivityInput> byCode = new HashMap<>();
        activities.forEach(a -> byCode.put(a.code(), a));
        int totalDuration = activities.stream().mapToInt(Cpm.ActivityInput::duration).sum();

        List<CurvePoint> curve = new ArrayList<>();
        for (var e : new TreeMap<>(weeks).entrySet()) {
            long planned = 0;
            long actual = 0;
            for (Row r : e.getValue()) {
                Cpm.ActivityInput a = byCode.get(r.activity());
                if (a != null) {
                    planned += (long) a.duration() * r.plannedPct();
                    actual += (long) a.duration() * r.actualPct();
                }
            }
            curve.add(new CurvePoint(e.getKey(), pct(planned, totalDuration), pct(actual, totalDuration)));
        }

        int week = weeks.keySet().stream().max(Integer::compare).orElseThrow();
        int cut = 5 * week;
        Map<String, Integer> delays = new HashMap<>();
        List<Deviation> deviations = new ArrayList<>();
        List<Row> status = new ArrayList<>(weeks.get(week));
        status.sort(Comparator.comparing(Row::activity));
        for (Row r : status) {
            Cpm.ActivityInput a = byCode.get(r.activity());
            if (a == null || (r.plannedPct() == 0 && r.actualPct() == 0)) {
                continue;
            }
            Cpm.ActivityTimes t = plan.activities().get(a.code());
            int delay;
            boolean lower = false;
            if (r.actualPct() >= r.plannedPct()) {
                delay = 0;
            } else if (r.actualPct() == 0) {
                delay = Math.max(0, cut - t.es());
                lower = true;
            } else {
                BigDecimal elapsed = BigDecimal.valueOf(r.actualPct()).multiply(BigDecimal.valueOf(a.duration()))
                        .divide(BigDecimal.valueOf(100));
                delay = Math.max(1, BigDecimal.valueOf(cut - t.es()).subtract(elapsed)
                        .setScale(0, RoundingMode.HALF_UP).intValueExact());
            }
            if (delay > 0) {
                delays.put(a.code(), delay);
            }
            deviations.add(new Deviation(a.code(), a.name(), r.plannedPct(), r.actualPct(), delay, lower,
                    t.totalFloat(), t.critical(), delay > t.totalFloat()));
        }
        List<Cpm.ActivityInput> shifted = activities.stream()
                .map(a -> new Cpm.ActivityInput(a.code(), a.name(), a.duration() + delays.getOrDefault(a.code(), 0)))
                .toList();
        int forecast = Cpm.solve(shifted, links).duration();
        boolean lowerBound = deviations.stream().anyMatch(d -> d.delayIsLowerBound() && d.delayDays() > 0);
        return new Result(week, curve, deviations, plan.duration(), forecast, forecast - plan.duration(), lowerBound);
    }

    private static BigDecimal pct(long weighted, int totalDuration) {
        return totalDuration == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(weighted).divide(BigDecimal.valueOf(totalDuration), 1, RoundingMode.HALF_UP);
    }
}
