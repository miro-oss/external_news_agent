"""Closed factual-repair categories without copying rejected prose into prompts."""

import re
from collections.abc import Sequence

_INTERNAL_REFERENCE = re.compile(
    r"(?<![A-Za-z0-9_])(?:claim(?:Id)?(?:\s*/\s*sourceSpanId)?|sourceSpanId)"
    r"\s*[:=]?\s*[\"'(\[]?(?:[1-9]\d*:\d+|s[1-9]\d*_\d+_\d+)"
    r"(?![A-Za-z0-9_:])",
    re.IGNORECASE,
)
_REFERENCE_ID = r"(?:[1-9]\d*:\d+|s[1-9]\d*_\d+_\d+)(?![A-Za-z0-9_:])"
_SELECTED_REFERENCE_LIST = re.compile(
    r"(?:[\[(]\s*(?:(?:근거|원문|참조|인용)\s*[:：]?\s*)?|"
    r"(?<!\w)(?:근거|원문|참조|인용)\s*[:：]\s*)"
    rf"(?P<refs>{_REFERENCE_ID}(?:\s*[,;·、]\s*{_REFERENCE_ID})*)"
    r"(?=\s*(?:[,;·、\])]|$))"
)
_REFERENCE_TOKEN = re.compile(_REFERENCE_ID)
_CATEGORIES = (
    ("unsupported_number", "근거에서 확인되지 않는 숫자: "),
    ("currency_amount", "근거와 일치하지 않는 통화·금액 숫자: "),
    ("numeric_context", "근거와 연결이 다른 숫자: "),
    ("date", "근거와 일치하지 않는 날짜 표현: "),
    ("company", "근거에서 확인되지 않는 기업명: "),
    ("source_binding", "근거에서 확인되지 않는 식별자: "),
    ("polarity", "근거와 반대되는 부정 표현이 포함되어 있습니다."),
    ("event_state", "근거에서 확인되지 않는 완료·착수·계약·중단 사실입니다."),
    ("event_state", "연결 원문에 없는 사건 단정:"),
    ("source_binding", "연결 원문에 없는 수치 지표 단정:"),
    ("unsupported_number", "근거에서 확인되지 않는 숫자 배수:"),
    ("event_state", "근거보다 확정적인 완료·착수·계약 사실을 단정했습니다."),
    ("source_binding", "근거의 주체·사건 연결과 다릅니다:"),
)
_GUIDANCE = {
    "internal_reference_in_prose": (
        "claimId/sourceSpanId 같은 내부 참조 ID가 자연어에 들어갔습니다. "
        "ID는 basis의 구조화 필드에만 두고 reason/condition은 업무 의미로 다시 쓰세요."
    ),
    "unsupported_number": (
        "선택 근거에 없는 숫자가 있습니다. 숫자 재요약을 빼거나 원문 수치·단위와 "
        "맞추세요. Schema const로 고정되지 않은 근거 필드만 같은 finding에서 "
        "실제 수치와 단위를 지원하는 근거로 다시 선택할 수 있습니다."
    ),
    "currency_amount": "통화·금액의 값과 배율을 선택 근거와 대조하고 다른 통화로 바꾸지 마세요.",
    "numeric_context": (
        "숫자가 있어도 주체·날짜·대상의 연결이 다릅니다. 서로 다른 사실의 숫자를 "
        "이어 붙이지 말고 각각의 근거에 맞춰 분리하세요."
    ),
    "date": "상대 날짜·기간 표현을 선택 근거와 맞추고 새 날짜나 기한을 만들지 마세요.",
    "company": (
        "선택 근거에 없는 기업·기관명이 있습니다. 이름과 그 이름의 부재 설명을 빼거나 "
        "원문의 실제 주체와 맞추세요. Schema const로 고정되지 않은 근거 필드만 "
        "같은 finding에서 실제 주체를 지원하는 근거로 다시 선택할 수 있습니다."
    ),
    "polarity": (
        "원문 사건의 긍정·부정 상태가 뒤집혔습니다. 정보 미확인과 사건의 미발생을 "
        "구분하고 원문의 상태를 유지하세요."
    ),
    "event_state": (
        "완료·착수·계약 등 사건 단계가 근거보다 확정적입니다. 계획·전망·실행·완료를 "
        "구분하고 원문에서 확인된 단계까지만 설명하세요."
    ),
    "source_binding": (
        "같은 주체·대상에 연결된 사건 상태를 유지하고 제품명·세대 식별자는 선택 원문과 "
        "전체 표기를 대조하세요. 다른 회사·시설의 완료 사실을 빌려 계획을 실행·완료로 "
        "바꾸지 말고 원문 구간별로 대조하세요."
    ),
}


def fact_repair_kinds(
    value: str, source: str, mismatches: list[str], *, refs: Sequence[str] = ()
) -> tuple[str, ...]:
    """Classify owned guard messages; only inspect actual prose for internal IDs.

    The source check preserves genuine source text about reference identifiers.
    These categories guide a rejected answer's repair; they never suppress an
    error, strip an identifier, change a reference, or authorize an output.
    """
    kinds = [
        kind
        for kind, prefix in _CATEGORIES
        if any(mismatch.startswith(prefix) for mismatch in mismatches)
    ]
    if any(
        mismatch.startswith("근거는 '") and " 단계인데 주장은 '" in mismatch
        for mismatch in mismatches
    ):
        kinds.append("event_state")
    labeled_reference = any(match[0] not in source for match in _INTERNAL_REFERENCE.finditer(value))
    # Recorded outputs also use "(근거: 101:0, 101:4, 연결 문장)".
    # Read citation lists in the actual rejected value and require a selected
    # reference. Guard/error prose cannot supply an identifier or a repair kind.
    selected_reference = any(
        token[0] in refs and token[0] not in source
        for citation in _SELECTED_REFERENCE_LIST.finditer(value)
        for token in _REFERENCE_TOKEN.finditer(citation["refs"])
    )
    if labeled_reference or selected_reference:
        kinds.insert(0, "internal_reference_in_prose")
    return tuple(dict.fromkeys(kinds))


def safe_fact_repair_kinds(kinds: object) -> tuple[str, ...]:
    """Closed rule identifiers suitable for logs; no rejected values or prose."""
    if type(kinds) is not tuple or not all(type(kind) is str for kind in kinds):
        return ()
    return tuple(kind for kind in _GUIDANCE if kind in kinds)


def fact_repair_guidance(kinds: tuple[str, ...]) -> str:
    """Unknown metadata cannot insert prose or values into a repair prompt."""
    return " ".join(f"[{kind}] {_GUIDANCE[kind]}" for kind in safe_fact_repair_kinds(kinds))
