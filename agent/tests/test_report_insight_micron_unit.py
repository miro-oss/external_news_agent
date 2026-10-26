"""Physical micron-unit prose must not invent a Micron company assertion."""

from types import SimpleNamespace

import pytest

from app.llm.report_insight_service import _validate_prose

SOURCE = (
    "The article details how intelligent in-situ sensing, such as Nordson’s WaferSense Auto "
    "Centering Sensor, replicates a production wafer’s form factor to capture real-time X, Y, "
    "and Z data under true process conditions — including vacuum — without disassembly."
)
RECORDED_REASON = (
    "기사에서 수작업 검증이 마이크론 단위 판단의 병목이라고 지적하고, WaferSense 센서가 "
    "공정 조건에서 실시간 데이터를 수집한다고 설명해 CHIP_MAKER의 공정 제어·수율 개선 "
    "업무와 직접 관련된다."
)


def validate(value):
    _validate_prose(
        [value], ["7889:2"], {"7889:2": SOURCE}, {"7889:2": SimpleNamespace(text=SOURCE)}
    )


def test_recorded_micron_unit_does_not_assert_an_unsupported_company():
    validate(RECORDED_REASON)


@pytest.mark.parametrize(
    "value",
    [
        "마이크론 단위의 위치 판단을 검토한다.",
        "마이크론 단위로 위치를 측정하는 센서다.",
    ],
)
def test_explicit_physical_unit_forms_are_not_company_names(value):
    validate(value)


@pytest.mark.parametrize(
    "value",
    [
        "마이크론은 해당 센서를 공급한다.",
        "마이크론 제품의 위치 제어를 검토한다.",
        "마이크론 단위 판단을 수행하며 마이크론이 센서를 공급한다.",
        "마이크론 단위 판단과 삼성전자의 센서 공급을 검토한다.",
    ],
)
def test_real_or_additional_company_assertions_are_still_rejected(value):
    with pytest.raises(ValueError, match="기업명"):
        validate(value)


def test_unit_disambiguation_does_not_remove_unsupported_numeric_values():
    with pytest.raises(ValueError, match="숫자"):
        validate("센서는 10마이크론 단위로 측정한다.")
