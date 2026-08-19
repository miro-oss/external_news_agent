import re
from dataclasses import dataclass

_BOUNDARY = re.compile(r"(?<=[.!?。！？])\s+|\n+")


@dataclass(frozen=True, slots=True)
class SentenceSplit:
    sentences: list[str]
    truncated: bool


def split_sentences(text: str, max_sentences: int) -> list[str]:
    """A0의 결정적 Mock 분할기. A1에서 언어별 sentence SSOT로 교체한다."""
    return split_sentences_with_meta(text, max_sentences).sentences


def split_sentences_with_meta(text: str, max_sentences: int) -> SentenceSplit:
    """문장 상한 적용 여부와 함께 결정적인 분할 결과를 반환한다."""
    normalized = "\n".join(" ".join(line.split()) for line in text.splitlines()).strip()
    if not normalized:
        return SentenceSplit(sentences=[], truncated=False)
    sentences = [part.strip() for part in _BOUNDARY.split(normalized) if part.strip()]
    return SentenceSplit(
        sentences=sentences[:max_sentences],
        truncated=len(sentences) > max_sentences,
    )
