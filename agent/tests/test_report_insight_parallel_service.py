"""Opted-in providers run independent MAPs together and synthesize only after merge."""

from decimal import Decimal
from threading import Barrier, Lock

import pytest
from test_report_insight_assessment import request
from test_report_insight_v4_pipeline import V4Provider, generate


class ParallelProvider(V4Provider):
    report_insight_max_concurrency = 3
    report_insight_credit_reservation = Decimal(0)

    def __init__(self, source):
        super().__init__(source, credits="0")
        self.lock = Lock()
        self.first_maps = Barrier(3, timeout=3)
        self.active = 0
        self.peak = 0
        self.completed_maps = set()

    def generate(self, **kwargs):
        stage = kwargs["response_schema"]["description"].split(":", 1)[1]
        is_map = stage.startswith("MAP-")
        if is_map:
            with self.lock:
                self.active += 1
                self.peak = max(self.peak, self.active)
            if stage in {"MAP-001", "MAP-002", "MAP-003"}:
                self.first_maps.wait()
        try:
            # Mutable test response builder state is protected independently
            # from the concurrent simulated HTTP time above.
            with self.lock:
                if not is_map:
                    assert self.completed_maps == {"MAP-001", "MAP-002", "MAP-003", "MAP-004"}
                result = super().generate(**kwargs)
                if is_map:
                    self.completed_maps.add(stage)
                return result
        finally:
            if is_map:
                with self.lock:
                    self.active -= 1


@pytest.mark.parametrize("plan", ["FREE", "PAID"])
def test_explicit_provider_parallelism_preserves_full_output_and_exact_usage(plan):
    source = request(ids=tuple(range(101, 121))).model_copy(update={"plan": plan})
    before = source.model_dump_json(by_alias=True)
    provider = ParallelProvider(source)
    output = generate(provider, source, AGENT_PROVIDER_CONCURRENCY=3)
    assert provider.peak == 3
    assert provider.completed_maps == {"MAP-001", "MAP-002", "MAP-003", "MAP-004"}
    assert [item.finding_id for item in output.insights[0].assessments] == list(range(101, 121))
    assert provider.stages["REDUCE-001"] == 1
    assert output.meta.input_tokens == 11 * len(provider.calls)
    assert output.meta.output_tokens == 7 * len(provider.calls)
    assert output.meta.cost_usd == pytest.approx(0.003 * len(provider.calls))
    assert output.meta.credits == 0
    assert source.model_dump_json(by_alias=True) == before
