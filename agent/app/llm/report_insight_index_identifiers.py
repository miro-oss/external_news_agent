"""Complete, source-comparable index names with unchanged literal spans."""

import re

_HORIZONTAL_SPACE = r"[^\S\r\n\v\f\x85\u2028\u2029]*"
# This recognizes a source identifier, not membership in a financial catalogue.
# A different number or suffix remains different, and newlines cannot join it.
S_AND_P_INDEX = re.compile(
    rf"(?<![A-Za-z0-9_])S{_HORIZONTAL_SPACE}&{_HORIZONTAL_SPACE}P{_HORIZONTAL_SPACE}"
    r"(?P<number>[0-9]+)(?![A-Za-z0-9_]|[.,][0-9])",
    re.IGNORECASE,
)
# Unlike the existing S&P spacing alias, only the complete attached spelling
# is recognized here. A source number/quantity alone cannot prove an index.
NASDAQ_INDEX = re.compile(
    r"(?<![가-힣A-Za-z0-9_])(?:나스닥|NASDAQ)(?P<number>[0-9]+)"
    r"(?![A-Za-z0-9_]|[.,][0-9])"
    r"(?=$|[^가-힣]|(?:지수)?(?:은|는|이|가|의|와|과|에|도|을|를)(?=$|[\s,.!?;])|"
    r"지수(?=$|[\s,.!?;]))",
    re.IGNORECASE,
)


def canonical_index_name(value: str) -> str:
    """Normalize only a recognized whole name; never rewrite the source text."""
    match = S_AND_P_INDEX.fullmatch(value)
    if match is None:
        raise ValueError("Expected a complete S&P index identifier")
    return "s&p" + match["number"]
