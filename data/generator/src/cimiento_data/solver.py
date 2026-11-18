"""Reference critical path solver (CPM), independent of the core service.

Definitions (docs/SYNTHETIC_DATA.md): finish-to-start links with integer lag >= 0,
integer durations > 0 in working days, critical means total float == 0.
Day indices are 0-based: an activity with ES=3 and duration 2 works days 3 and 4
and has EF=5.
"""

from __future__ import annotations

from dataclasses import dataclass

import networkx as nx


class ScheduleError(ValueError):
    pass


@dataclass(frozen=True)
class ActivityResult:
    id: str
    duration: int
    es: int
    ef: int
    ls: int
    lf: int

    @property
    def total_float(self) -> int:
        return self.ls - self.es

    @property
    def critical(self) -> bool:
        return self.total_float == 0


@dataclass(frozen=True)
class CpmResult:
    duration: int
    activities: dict[str, ActivityResult]

    @property
    def critical(self) -> list[str]:
        acts = [a for a in self.activities.values() if a.critical]
        return [a.id for a in sorted(acts, key=lambda a: (a.es, a.id))]


def solve(durations: dict[str, int], links: list[tuple[str, str, int]]) -> CpmResult:
    if not durations:
        raise ScheduleError("no activities")
    for act, dur in durations.items():
        if not isinstance(dur, int) or dur <= 0:
            raise ScheduleError(f"duration of {act} must be a positive integer")
    graph = nx.DiGraph()
    graph.add_nodes_from(sorted(durations))
    for pred, succ, lag in links:
        if pred not in durations or succ not in durations:
            raise ScheduleError(f"link {pred}->{succ} references an unknown activity")
        if not isinstance(lag, int) or lag < 0:
            raise ScheduleError(f"lag of {pred}->{succ} must be a non-negative integer")
        if pred == succ:
            raise ScheduleError(f"self link on {pred}")
        if graph.has_edge(pred, succ):
            raise ScheduleError(f"duplicate link {pred}->{succ}")
        graph.add_edge(pred, succ, lag=lag)
    if not nx.is_directed_acyclic_graph(graph):
        cycle = nx.find_cycle(graph)
        raise ScheduleError(f"dependency cycle: {cycle}")

    order = list(nx.lexicographical_topological_sort(graph))
    es: dict[str, int] = {}
    for node in order:
        es[node] = max(
            (es[p] + durations[p] + graph.edges[p, node]["lag"] for p in graph.predecessors(node)),
            default=0,
        )
    ef = {n: es[n] + durations[n] for n in order}
    project = max(ef.values())
    lf: dict[str, int] = {}
    for node in reversed(order):
        lf[node] = min(
            (lf[s] - durations[s] - graph.edges[node, s]["lag"] for s in graph.successors(node)),
            default=project,
        )
    acts = {
        n: ActivityResult(n, durations[n], es[n], ef[n], lf[n] - durations[n], lf[n]) for n in order
    }
    return CpmResult(project, acts)
