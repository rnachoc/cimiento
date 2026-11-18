package dev.cimiento.core.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * Critical path method on finish-to-start links with integer lags.
 *
 * <p>Definitions (docs/SYNTHETIC_DATA.md): integer durations &gt; 0 in working days, lags &ge; 0,
 * day indices are 0-based (ES=3, duration 2 means days 3 and 4, EF=5), critical means total
 * float == 0. Topological order is lexicographic by code, so the result is deterministic.
 */
public final class Cpm {

    private Cpm() {
    }

    public record ActivityInput(String code, String name, int duration) {
    }

    public record Link(String from, String to, int lag) {
    }

    public record ActivityTimes(String code, int duration, int es, int ef, int ls, int lf) {
        public int totalFloat() {
            return ls - es;
        }

        public boolean critical() {
            return totalFloat() == 0;
        }
    }

    public record Result(int duration, Map<String, ActivityTimes> activities) {
        /** Critical activities ordered by early start, then code. */
        public List<String> critical() {
            return activities.values().stream()
                    .filter(ActivityTimes::critical)
                    .sorted(Comparator.comparingInt(ActivityTimes::es).thenComparing(ActivityTimes::code))
                    .map(ActivityTimes::code)
                    .toList();
        }
    }

    /** Validates the graph and returns every problem found (empty list when valid). */
    public static List<String> validate(List<ActivityInput> activities, List<Link> links) {
        List<String> errors = new ArrayList<>();
        if (activities.isEmpty()) {
            errors.add("no activities");
            return errors;
        }
        Set<String> codes = new HashSet<>();
        for (ActivityInput a : activities) {
            if (a.code() == null || a.code().isBlank()) {
                errors.add("activity without code");
            } else if (!codes.add(a.code())) {
                errors.add("duplicate activity " + a.code());
            }
            if (a.duration() <= 0) {
                errors.add("duration of " + a.code() + " must be positive");
            }
        }
        Set<String> seen = new HashSet<>();
        for (Link l : links) {
            if (!codes.contains(l.from()) || !codes.contains(l.to())) {
                errors.add("link " + l.from() + "->" + l.to() + " references an unknown activity");
            }
            if (l.from() != null && l.from().equals(l.to())) {
                errors.add("self link on " + l.from());
            }
            if (l.lag() < 0) {
                errors.add("lag of " + l.from() + "->" + l.to() + " must not be negative");
            }
            if (!seen.add(l.from() + "->" + l.to())) {
                errors.add("duplicate link " + l.from() + "->" + l.to());
            }
        }
        if (errors.isEmpty()) {
            List<String> cycle = remainingAfterTopologicalSort(activities, links);
            if (!cycle.isEmpty()) {
                errors.add("dependency cycle among " + cycle);
            }
        }
        return errors;
    }

    /** Computes the schedule. Throws IllegalArgumentException when {@link #validate} fails. */
    public static Result solve(List<ActivityInput> activities, List<Link> links) {
        List<String> errors = validate(activities, links);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        Map<String, Integer> duration = new HashMap<>();
        activities.forEach(a -> duration.put(a.code(), a.duration()));
        Map<String, List<Link>> preds = new HashMap<>();
        Map<String, List<Link>> succs = new HashMap<>();
        for (Link l : links) {
            preds.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(l);
            succs.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l);
        }
        List<String> order = topologicalOrder(activities, links);

        Map<String, Integer> es = new HashMap<>();
        for (String n : order) {
            int start = 0;
            for (Link l : preds.getOrDefault(n, List.of())) {
                start = Math.max(start, es.get(l.from()) + duration.get(l.from()) + l.lag());
            }
            es.put(n, start);
        }
        int project = order.stream().mapToInt(n -> es.get(n) + duration.get(n)).max().orElseThrow();
        Map<String, Integer> lf = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            String n = order.get(i);
            int finish = project;
            for (Link l : succs.getOrDefault(n, List.of())) {
                finish = Math.min(finish, lf.get(l.to()) - duration.get(l.to()) - l.lag());
            }
            lf.put(n, finish);
        }
        Map<String, ActivityTimes> result = new LinkedHashMap<>();
        for (String n : new TreeMap<>(duration).keySet()) {
            int d = duration.get(n);
            result.put(n, new ActivityTimes(n, d, es.get(n), es.get(n) + d, lf.get(n) - d, lf.get(n)));
        }
        return new Result(project, result);
    }

    private static List<String> topologicalOrder(List<ActivityInput> activities, List<Link> links) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> next = new HashMap<>();
        activities.forEach(a -> indegree.put(a.code(), 0));
        for (Link l : links) {
            indegree.merge(l.to(), 1, Integer::sum);
            next.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to());
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((k, v) -> {
            if (v == 0) {
                ready.add(k);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String n = ready.poll();
            order.add(n);
            for (String s : next.getOrDefault(n, List.of())) {
                if (indegree.merge(s, -1, Integer::sum) == 0) {
                    ready.add(s);
                }
            }
        }
        return order;
    }

    private static List<String> remainingAfterTopologicalSort(List<ActivityInput> activities, List<Link> links) {
        Set<String> sorted = new HashSet<>(topologicalOrder(activities, links));
        return activities.stream().map(ActivityInput::code).filter(c -> !sorted.contains(c)).sorted().toList();
    }
}
