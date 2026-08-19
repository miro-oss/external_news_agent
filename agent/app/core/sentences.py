import re

_BOUNDARY = re.compile(r"(?<=[.!?。！？])\s+|\n+")


def split_sentences(text: str, max_sentences: int) -> list[str]:
    """A0의 결정적 Mock 분할기. A1에서 언어별 sentence SSOT로 교체한다."""
    normalized = " ".join(text.split())
    if not normalized:
        return []
    return [part.strip() for part in _BOUNDARY.split(normalized) if part.strip()][:max_sentences]
