"""Reference CPM solver checked against hand-computed schedules."""

from __future__ import annotations

import networkx as nx
import pytest

from cimiento_data.solver import ScheduleError, solve


def test_chain():
    res = solve({"A": 3, "B": 2, "C": 4}, [("A", "B", 0), ("B", "C", 0)])
    assert res.duration == 9
    assert res.critical == ["A", "B", "C"]
    assert (res.activities["C"].es, res.activities["C"].ef) == (5, 9)


def test_parallel_branch_has_float():
    res = solve(
        {"A": 2, "B": 5, "C": 3, "D": 1},
        [("A", "B", 0), ("A", "C", 0), ("B", "D", 0), ("C", "D", 0)],
    )
    assert res.duration == 8
    assert res.critical == ["A", "B", "D"]
    c = res.activities["C"]
    assert (c.es, c.ef, c.ls, c.lf, c.total_float) == (2, 5, 4, 7, 2)


def test_two_critical_paths():
    res = solve(
        {"A": 1, "B": 3, "C": 3, "D": 1},
        [("A", "B", 0), ("A", "C", 0), ("B", "D", 0), ("C", "D", 0)],
    )
    assert res.duration == 5
    assert res.critical == ["A", "B", "C", "D"]


def test_lag():
    res = solve({"A": 2, "B": 1}, [("A", "B", 3)])
    assert res.activities["B"].es == 5
    assert res.duration == 6
    assert res.critical == ["A", "B"]


def test_independent_end_activity_floats_to_project_end():
    res = solve({"A": 4, "B": 1}, [])
    assert res.duration == 4
    assert res.activities["B"].total_float == 3
    assert res.critical == ["A"]


@pytest.mark.parametrize(
    "durations,links",
    [
        ({"A": 1, "B": 1}, [("A", "B", 0), ("B", "A", 0)]),
        ({"A": 1, "B": 1, "C": 1}, [("A", "B", 0), ("B", "C", 0), ("C", "A", 0)]),
        ({"A": 0}, []),
        ({"A": -1}, []),
        ({"A": 1}, [("A", "X", 0)]),
        ({"A": 1, "B": 1}, [("A", "B", -1)]),
        ({"A": 1}, [("A", "A", 0)]),
        ({"A": 1, "B": 1}, [("A", "B", 0), ("A", "B", 1)]),
        ({}, []),
    ],
)
def test_invalid_schedules_are_rejected(durations, links):
    with pytest.raises(ScheduleError):
        solve(durations, links)


def longest_path_duration(durations, links) -> int:
    """Independent check: project duration as a longest path in an edge-weighted DAG."""
    g = nx.DiGraph()
    for a, d in durations.items():
        g.add_edge("START", a, weight=0)
        g.add_edge(a, "END", weight=d)
    for p, s, lag in links:
        g.add_edge(p, s, weight=durations[p] + lag)
    return nx.dag_longest_path_length(g, weight="weight")


def test_matches_longest_path_on_random_dags():
    import random

    r = random.Random(3)
    for _ in range(300):
        n = r.randint(1, 12)
        acts = [f"X{i:02d}" for i in range(n)]
        durations = {a: r.randint(1, 20) for a in acts}
        links = [
            (acts[i], acts[j], r.randint(0, 3))
            for i in range(n)
            for j in range(i + 1, n)
            if r.random() < 0.3
        ]
        res = solve(durations, links)
        assert res.duration == longest_path_duration(durations, links)
        for a in res.activities.values():
            assert 0 <= a.es <= a.ls and a.ef <= a.lf <= res.duration
