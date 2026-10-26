"""Bind a forecast IPO amount annotation to its explicit issuer, not its parent."""

import re

from app.core.evidence import (
    _CURRENCY_AMOUNT,
    _PREFIX_CURRENCY_AMOUNT,
    _contextual_number_mismatches,
    _currency_amounts,
    _date_number_anchors,
    _date_terms,
    _normalize,
    _quantity_values,
)

_ISSUER_ANNOTATION = re.compile(
    r"(?<![가-힣A-Za-z0-9])(?P<issuer>[가-힣A-Za-z][가-힣A-Za-z0-9&.-]{1,35}?)의\s*"
    r"(?:IPO|기업\s*공개|상장)\s*\((?P<body>[^()\n]{1,80})\)",
    re.I,
)
_SENTENCE_BREAK = re.compile(r"(?<!\d)[.!?](?!\d)|[。;；\n]")
_JOINT_END = re.compile(r"(?:와|과|및|외|·|,|and|&)\s*$", re.I)
# A preceding company may supply a separate qualitative valuation topic. Do
# not accept an arbitrary prefix: "모회사의 매출은 ... IPO(금액) 규모와 같다"
# explicitly reassigns the annotated amount to another measure/owner.
_COORDINATED_VALUATION = re.compile(
    r"(?:(?:기사|원문|자료|보고서)의\s+)?"
    r"[가-힣A-Za-z][가-힣A-Za-z0-9&.-]{1,35}(?:의)?\s+"
    r"(?:밸류에이션|기업가치|주가)\s*(?:할인|프리미엄|재평가)\s*"
    r"(?:및|과|와)\s*(?:(?:자회사|계열사|관계사)\s*)?$"
)
_ANALYTICAL_NOUN = r"(?:주주가치|기업가치|밸류에이션|수익성|투자)"
_ANALYTICAL_NOUNS = rf"{_ANALYTICAL_NOUN}(?:\s*[·/]\s*{_ANALYTICAL_NOUN}){{0,3}}"
# No free prose is admitted inside this exception. A closing "판단과 연결된다"
# cannot prove an earlier transfer, allocation or completed financing event.
_ANALYTICAL_TOPIC = re.compile(
    rf"(?:은|는|이|가)\s+(?:{_ANALYTICAL_NOUNS}\s*(?:구조)?에\s*"
    r"(?:직접적(?:인)?\s*)?영향\s*요인으로,\s*)?"
    rf"(?:투자\s*관점의\s*)?{_ANALYTICAL_NOUNS}\s*판단(?:에|과|와)\s*"
    r"(?:(?:직접|조건부로|밀접하게)\s*)?연결된다[.!?。]?\s*$"
)
_ISSUER_FORECAST_PREFIX = re.compile(
    r"(?<![가-힣A-Za-z0-9])(?P<issuer>[가-힣A-Za-z][가-힣A-Za-z0-9&.-]{1,35}?)의\s*"
    r"(?P<approx>약|대략)?\s*$",
    re.I,
)
_REPORTED_VALUATION = re.compile(
    r"(?:원문|기사|자료|보고서)(?:은|는)\s+"
    r"[가-힣A-Za-z][가-힣A-Za-z0-9&.-]{1,35}의\s+"
    r"(?:밸류에이션|기업가치|주가)\s*(?:할인|프리미엄|재평가)\s*분석(?:과|와)\s*$"
)
_REPORTED_FUNDING_FORECAST = re.compile(
    r"\s*규모\s*(?:IPO|기업\s*공개|상장)\s*조달\s*(?:전망|예상)(?:을|를)\s*"
    r"(?:함께\s*)?제시(?:하고\s*있어|한다|했다)(?=\s|[,.;!?。]|$)",
    re.I,
)
_FUNDING_FORECAST = re.compile(
    r"\s*(?:\((?P<conversion>[^()\n]{1,60})\))?\s*(?:을|를)?\s*"
    r"조달할\s+것으로\s+(?:예상|전망)된다\s*$"
)


def supported_ipo_amount_context(value: str, source: str) -> str | None:
    """Discharge only a single legacy whole-clause numeric context error.

    A possessive issuer and a forecast-only monetary annotation name one IPO
    amount in the topic of an analytical sentence, optionally coordinated with
    a separate qualitative valuation topic. A reported valuation analysis may
    also precede an explicitly owned amount and IPO funding forecast. A selected
    raw sentence must say that exact issuer expects to raise that amount through
    its IPO. Arbitrary
    preceding/following predicates, quantities, clocks, joint issuers, changed
    currencies and actual-result annotations retain the ordinary guard.
    """
    value, source = _normalize(value), _normalize(source)
    annotations = list(_ISSUER_ANNOTATION.finditer(value))
    amounts = _currency_amounts(value)
    if (
        len(amounts) != 1
        or _quantity_values(value) != {amounts[0][2]}
        or _date_number_anchors(value)
        or _date_terms(value)
    ):
        return None
    start, end, amount = amounts[0]
    if len(annotations) == 1:
        annotation = annotations[0]
        prefix = value[: annotation.start()].strip()
        suffix = value[annotation.end() :].strip()
        if (
            prefix
            and _COORDINATED_VALUATION.fullmatch(prefix) is None
            or _ANALYTICAL_TOPIC.fullmatch(suffix) is None
        ):
            return None
        if not annotation.start("body") <= start < end <= annotation.end("body"):
            return None
        before = value[annotation.start("body") : start].strip()
        after = value[end : annotation.end("body")].strip()
        if before not in {"", "약", "대략"} or after not in {"예상", "전망"}:
            return None
        issuer = re.escape(annotation["issuer"])
    elif not annotations:
        # "parent valuation analysis and issuer's amount IPO forecast" names
        # two independent topics. Recheck the whole amount-bearing remainder;
        # another quantitative owner/predicate cannot inherit this exception.
        forecast = _ISSUER_FORECAST_PREFIX.search(value[:start])
        if (
            forecast is None
            or _REPORTED_VALUATION.fullmatch(value[: forecast.start()]) is None
            or _REPORTED_FUNDING_FORECAST.match(value[end:]) is None
            or _contextual_number_mismatches(value[forecast.start() :], source)
        ):
            return None
        issuer = re.escape(forecast["issuer"])
        before = forecast["approx"] or ""
    else:
        return None
    source_prefix = re.compile(
        rf"(?<![가-힣A-Za-z0-9]){issuer}(?:은|는|이|가)\s*"
        r"(?:이번\s+)?(?:IPO|기업\s*공개|상장)(?:을|를)\s*통해\s*"
        r"(?P<approx>약|대략)?\s*$",
        re.I,
    )
    for sentence in _SENTENCE_BREAK.split(source):
        for left, right, source_amount in _currency_amounts(sentence):
            if source_amount != amount:
                continue
            prefix = source_prefix.search(sentence[:left])
            suffix = _FUNDING_FORECAST.fullmatch(sentence[right:])
            if (
                prefix is None
                or suffix is None
                or _JOINT_END.search(sentence[: prefix.start()])
                or prefix["approx"] is not None
                and not before
            ):
                continue
            conversion = suffix["conversion"]
            if conversion is not None:
                conversion = re.sub(r"^(?:약|대략)\s*", "", conversion.strip(), count=1)
                if not any(
                    currency.fullmatch(conversion)
                    for currency in (_CURRENCY_AMOUNT, _PREFIX_CURRENCY_AMOUNT)
                ):
                    continue
            mismatches = _contextual_number_mismatches(value, source)
            return mismatches[0] if len(mismatches) == 1 else None
    return None
