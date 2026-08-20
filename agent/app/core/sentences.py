import re
from dataclasses import dataclass

_TERMINATORS = frozenset(".!?。！？")
_CLOSERS = frozenset("\"'”’)]}")
_TERMINATORS_OR_CLOSERS = _TERMINATORS | _CLOSERS
_DATE_SUFFIX = re.compile(r"(?:^|\s)\d{2,4}\.\s*\d{1,2}\.\s*\d{1,2}\.$")
_NEVER_TERMINAL_ABBREVIATIONS = frozenset(
    {
        "dr.",
        "e.u.",
        "e.g.",
        "i.e.",
        "mr.",
        "mrs.",
        "ms.",
        "prof.",
        "u.k.",
        "u.n.",
        "u.s.",
        "vs.",
    }
)


@dataclass(frozen=True, slots=True)
class SentenceSplit:
    sentences: list[str]
    truncated: bool


def split_sentences(text: str, max_sentences: int) -> list[str]:
    """Agent 실제 분석과 evidence id가 함께 사용하는 결정적인 문장 배열."""
    return split_sentences_with_meta(text, max_sentences).sentences


def split_sentences_with_meta(text: str, max_sentences: int) -> SentenceSplit:
    """한국어·영문 문장 경계와 문장 상한 적용 여부를 함께 반환한다."""
    normalized = _normalize(text)
    if not normalized:
        return SentenceSplit(sentences=[], truncated=False)

    sentences: list[str] = []
    start = 0
    index = 0
    while index < len(normalized):
        char = normalized[index]
        if char == "\n":
            _append(sentences, normalized[start:index])
            start = index + 1
            index += 1
            continue
        if char not in _TERMINATORS or not _is_boundary(normalized, index):
            index += 1
            continue

        end = index + 1
        while end < len(normalized) and normalized[end] in _TERMINATORS_OR_CLOSERS:
            end += 1
        _append(sentences, normalized[start:end])
        while end < len(normalized) and normalized[end] == " ":
            end += 1
        start = end
        index = end

    _append(sentences, normalized[start:])
    return SentenceSplit(
        sentences=sentences[:max_sentences],
        truncated=len(sentences) > max_sentences,
    )


def _normalize(text: str) -> str:
    lines = [" ".join(line.split()) for line in text.splitlines()]
    return "\n".join(line for line in lines if line).strip()


def _append(sentences: list[str], candidate: str) -> None:
    value = candidate.strip()
    if value:
        sentences.append(value)


def _is_boundary(text: str, index: int) -> bool:
    char = text[index]
    if char != ".":
        return _followed_by_space_or_end(text, index)
    if _is_decimal_point(text, index):
        return False
    if _continues_numeric_notation(text, index):
        return False
    if _is_numbered_list_marker(text, index):
        return False

    token = _period_token(text, index).lower()
    if token in _NEVER_TERMINAL_ABBREVIATIONS:
        return False
    next_char = _next_content_char(text, index)
    if _is_acronym(token) and next_char is not None and next_char.islower():
        return False
    return _followed_by_space_or_end(text, index)


def _followed_by_space_or_end(text: str, index: int) -> bool:
    cursor = index + 1
    while cursor < len(text) and text[cursor] in _TERMINATORS_OR_CLOSERS:
        cursor += 1
    return cursor == len(text) or text[cursor].isspace()


def _is_decimal_point(text: str, index: int) -> bool:
    return (
        index > 0
        and index + 1 < len(text)
        and text[index - 1].isdigit()
        and text[index + 1].isdigit()
    )


def _continues_numeric_notation(text: str, index: int) -> bool:
    cursor = index + 1
    while cursor < len(text) and text[cursor].isspace():
        cursor += 1
    if cursor < len(text) and text[cursor].isdigit():
        return True

    window_start = max(0, index - 30)
    return _DATE_SUFFIX.search(text[window_start : index + 1]) is not None


def _is_numbered_list_marker(text: str, index: int) -> bool:
    start = index
    while start > 0 and text[start - 1].isdigit():
        start -= 1
    number = text[start:index]
    return (
        len(number) == 1
        and (start == 0 or text[start - 1].isspace())
        and _next_content_char(text, index) is not None
    )


def _period_token(text: str, index: int) -> str:
    start = index
    while start > 0 and (text[start - 1].isalpha() or text[start - 1] == "."):
        start -= 1
    return text[start : index + 1]


def _next_content_char(text: str, index: int) -> str | None:
    cursor = index + 1
    while cursor < len(text) and (text[cursor].isspace() or text[cursor] in _CLOSERS):
        cursor += 1
    return text[cursor] if cursor < len(text) else None


def _is_acronym(token: str) -> bool:
    parts = token.split(".")
    letters = [part for part in parts if part]
    return len(letters) >= 2 and all(len(part) == 1 and part.isalpha() for part in letters)
