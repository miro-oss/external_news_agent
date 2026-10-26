"""Real worker synchronization proves ordering, bounded fanout and failure drain."""

from threading import Barrier, Event, Lock

import pytest

from app.core.errors import AgentError
from app.llm.report_insight_map_execution import run_report_maps


def test_parallel_maps_overlap_but_return_in_input_order():
    barrier = Barrier(3, timeout=3)
    last_started = Event()
    lock = Lock()
    active = peak = 0
    completed = []

    def evaluate(index, item):
        nonlocal active, peak
        with lock:
            active += 1
            peak = max(peak, active)
        if index < 3:
            barrier.wait()
        if index == 0:
            assert last_started.wait(3)
        if index == 2:
            last_started.set()
        with lock:
            active -= 1
            completed.append(index)
        return (index, item)

    result = run_report_maps(
        list("abcde"), evaluate, concurrency=3, cancel_pending=lambda: pytest.fail("unexpected")
    )
    assert result == list(enumerate("abcde"))
    assert peak == 3
    assert sorted(completed) == list(range(5))


def test_first_failure_blocks_queued_work_and_drains_admitted_siblings():
    barrier = Barrier(3, timeout=3)
    cancelled = Event()
    drained = []
    admitted = []
    original = AgentError(502, "SCHEMA_VIOLATION", "invalid MAP")

    def evaluate(index, item):
        admitted.append(index)
        barrier.wait()
        if index == 1:
            raise original
        assert cancelled.wait(3)
        # Real siblings settle before the error can leave run_report_maps.
        drained.append(index)
        return item

    with pytest.raises(AgentError) as caught:
        run_report_maps(range(8), evaluate, concurrency=3, cancel_pending=cancelled.set)
    assert caught.value is original
    assert set(admitted) == {0, 1, 2}
    assert set(drained) == {0, 2}


def test_serial_provider_stops_without_starting_later_batches():
    seen = []

    def evaluate(index, item):
        seen.append(index)
        if index == 1:
            raise ValueError("bad batch")
        return item

    with pytest.raises(ValueError, match="bad batch"):
        run_report_maps(range(4), evaluate, concurrency=1, cancel_pending=lambda: None)
    assert seen == [0, 1]


def test_sibling_cancellation_cannot_replace_delayed_original_failure():
    barrier = Barrier(2, timeout=3)
    cancellation_seen = Event()
    original = AgentError(502, "SCHEMA_VIOLATION", "original provider rejection")

    def evaluate(index, item):
        barrier.wait()
        if index == 0:
            raise AgentError(
                503,
                "PROVIDER_UNAVAILABLE",
                "sibling cancelled before sending",
                {"pipelineCancelled": True, "requestNotStarted": True},
            )
        assert cancellation_seen.wait(3)
        raise original

    with pytest.raises(AgentError) as caught:
        run_report_maps([0, 1], evaluate, concurrency=3, cancel_pending=cancellation_seen.set)
    assert caught.value is original


@pytest.mark.parametrize("items", [[], ["one"]])
def test_empty_and_single_map_keep_serial_behavior(items):
    assert (
        run_report_maps(items, lambda index, item: item, concurrency=3, cancel_pending=lambda: None)
        == items
    )
