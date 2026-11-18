package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cimiento.core.rules.Cpm.ActivityInput;
import dev.cimiento.core.rules.Cpm.Link;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CpmTest {

    private static ActivityInput a(String code, int d) {
        return new ActivityInput(code, code, d);
    }

    private static Link l(String from, String to, int lag) {
        return new Link(from, to, lag);
    }

    @Test
    void chain() {
        var r = Cpm.solve(List.of(a("A", 3), a("B", 2), a("C", 4)), List.of(l("A", "B", 0), l("B", "C", 0)));
        assertThat(r.duration()).isEqualTo(9);
        assertThat(r.critical()).containsExactly("A", "B", "C");
        assertThat(r.activities().get("C").es()).isEqualTo(5);
        assertThat(r.activities().get("C").ef()).isEqualTo(9);
    }

    @Test
    void parallelBranchHasFloat() {
        var r = Cpm.solve(List.of(a("A", 2), a("B", 5), a("C", 3), a("D", 1)),
                List.of(l("A", "B", 0), l("A", "C", 0), l("B", "D", 0), l("C", "D", 0)));
        assertThat(r.duration()).isEqualTo(8);
        assertThat(r.critical()).containsExactly("A", "B", "D");
        var c = r.activities().get("C");
        assertThat(List.of(c.es(), c.ef(), c.ls(), c.lf(), c.totalFloat())).containsExactly(2, 5, 4, 7, 2);
    }

    @Test
    void twoCriticalPaths() {
        var r = Cpm.solve(List.of(a("A", 1), a("B", 3), a("C", 3), a("D", 1)),
                List.of(l("A", "B", 0), l("A", "C", 0), l("B", "D", 0), l("C", "D", 0)));
        assertThat(r.duration()).isEqualTo(5);
        assertThat(r.critical()).containsExactly("A", "B", "C", "D");
    }

    @Test
    void lagDelaysSuccessor() {
        var r = Cpm.solve(List.of(a("A", 2), a("B", 1)), List.of(l("A", "B", 3)));
        assertThat(r.activities().get("B").es()).isEqualTo(5);
        assertThat(r.duration()).isEqualTo(6);
        assertThat(r.critical()).containsExactly("A", "B");
    }

    @Test
    void unlinkedActivityFloatsToProjectEnd() {
        var r = Cpm.solve(List.of(a("A", 4), a("B", 1)), List.of());
        assertThat(r.activities().get("B").totalFloat()).isEqualTo(3);
        assertThat(r.critical()).containsExactly("A");
    }

    @Test
    void resultDoesNotDependOnInputOrder() {
        var acts = new ArrayList<>(List.of(a("A", 2), a("B", 5), a("C", 3), a("D", 1)));
        var links = new ArrayList<>(List.of(l("A", "B", 0), l("A", "C", 1), l("B", "D", 0), l("C", "D", 2)));
        var first = Cpm.solve(acts, links);
        java.util.Collections.reverse(acts);
        java.util.Collections.reverse(links);
        assertThat(Cpm.solve(acts, links)).isEqualTo(first);
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of(List.of(a("A", 1), a("B", 1)), List.of(l("A", "B", 0), l("B", "A", 0)), "cycle"),
                Arguments.of(List.of(a("A", 1), a("B", 1), a("C", 1)),
                        List.of(l("A", "B", 0), l("B", "C", 0), l("C", "A", 0)), "cycle"),
                Arguments.of(List.of(a("A", 0)), List.of(), "positive"),
                Arguments.of(List.of(a("A", -2)), List.of(), "positive"),
                Arguments.of(List.of(a("A", 1)), List.of(l("A", "X", 0)), "unknown"),
                Arguments.of(List.of(a("A", 1), a("B", 1)), List.of(l("A", "B", -1)), "negative"),
                Arguments.of(List.of(a("A", 1)), List.of(l("A", "A", 0)), "self"),
                Arguments.of(List.of(a("A", 1), a("B", 1)), List.of(l("A", "B", 0), l("A", "B", 2)), "duplicate link"),
                Arguments.of(List.of(a("A", 1), a("A", 2)), List.of(), "duplicate activity"),
                Arguments.of(List.of(), List.of(), "no activities"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invalidGraphsAreRejected(List<ActivityInput> acts, List<Link> links, String message) {
        assertThat(Cpm.validate(acts, links)).anyMatch(e -> e.contains(message));
        assertThatThrownBy(() -> Cpm.solve(acts, links)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Independent check: project duration by memoised longest path over successors. */
    @Test
    void matchesBruteForceLongestPathOnRandomDags() {
        Random rnd = new Random(3);
        for (int round = 0; round < 500; round++) {
            int n = 1 + rnd.nextInt(14);
            List<ActivityInput> acts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                acts.add(a(String.format("X%02d", i), 1 + rnd.nextInt(20)));
            }
            List<Link> links = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (rnd.nextDouble() < 0.3) {
                        links.add(l(acts.get(i).code(), acts.get(j).code(), rnd.nextInt(4)));
                    }
                }
            }
            var r = Cpm.solve(acts, links);
            Map<String, Integer> memo = new HashMap<>();
            int longest = acts.stream().mapToInt(x -> tail(x.code(), acts, links, memo)).max().orElseThrow();
            assertThat(r.duration()).isEqualTo(longest);
            for (var t : r.activities().values()) {
                assertThat(t.es()).isBetween(0, t.ls());
                assertThat(t.lf()).isBetween(t.ef(), r.duration());
                // an activity is critical exactly when its longest chain through it equals the project
                assertThat(t.critical()).isEqualTo(t.es() + tail(t.code(), acts, links, memo) == r.duration());
            }
        }
    }

    private static int tail(String code, List<ActivityInput> acts, List<Link> links, Map<String, Integer> memo) {
        Integer cached = memo.get(code);
        if (cached != null) {
            return cached;
        }
        int d = acts.stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow().duration();
        int best = d;
        for (Link link : links) {
            if (link.from().equals(code)) {
                best = Math.max(best, d + link.lag() + tail(link.to(), acts, links, memo));
            }
        }
        memo.put(code, best);
        return best;
    }
}
