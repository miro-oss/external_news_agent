"""Separate sourced trading observations from investment recommendations.

This is a conservative expression check, not a factual validator. Each trading
mention needs an observational form and corresponding selected source wording.
Existing guards still verify its actor, amount, timing and factual certainty.
Only callers can authenticate and remove server-rendered source quotations.
"""

import re
import unicodedata

_TRADE = re.compile(r"매수|매도")
_TARGET_NAME = re.compile(r"목표\s*(?:주가|가격)|주가\s*목표|목표가")
_BARE_TARGET_PRICE = re.compile(
    r"(?:목표\s*(?:주가|가격)|목표가)(?:를|는|의|을|은)?\s*"
    r"(?::\s*)?(?:주당\s*)?"
    r"(?:[0-9]|[영일이삼사오육칠팔구십백천만억조]+(?:원|달러|엔)|"
    r"상향|하향|인상|인하|조정|변경|산정|설정|추천|제시|전망|높|낮)"
)
_TARGET_PARTICLE = r"(?:를|는|의|을|은|가|이)?\s*"
_PRICE_AMOUNT = re.compile(
    r"(?:[₩$¥€]\s*[0-9][0-9,.]*|"
    r"(?:[0-9][0-9,.]*\s*(?:조|억|천|백|만)?|[영일이삼사오육칠팔구십백천만억조]+)"
    r"\s*(?:원|달러|엔|유로|%|퍼센트))"
)
_PRICE_BEFORE_TARGET = re.compile(_PRICE_AMOUNT.pattern + r"(?:으로|로|인)?\s*$")
_AMOUNT_PROPOSAL = re.compile(
    _PRICE_AMOUNT.pattern + r"(?:으로|로|를|을|이)?\s*(?:새롭게\s*)?(?:제시|설정|산정|추천)"
)
_TARGET_NUMERIC_VALUE = re.compile(_TARGET_PARTICLE + r"(?::\s*)?(?:주당\s*)?(?:약\s*)?[0-9]")
_CHANGE_VERB = (
    r"(?:(?:상향|하향|인상|인하|조정|변경)(?:\s*조정)?\s*(?:했|하였|됐|되었)|"
    r"높였|낮췄|올렸|내렸)"
    r"(?:다|다고|다는|으며|고|지만|는데)"
)
_TARGET_COMPLETED_CHANGE = re.compile(
    _TARGET_PARTICLE
    + r"(?:(?:잇달아|일제히|각각|모두|추가로|대폭|소폭)\s*)*"
    + r"(?:기존\s*)?(?:"
    + _PRICE_AMOUNT.pattern
    + r"(?:에서|으로|로)?\s*)*"
    + _CHANGE_VERB
)
_TARGET_CHANGE_REPORT = re.compile(
    _TARGET_PARTICLE + r"(?:(?:(?:상향|하향|인상|인하)\s*)?(?:조정|변경)|"
    r"상향|하향|인상|인하|산정\s*근거\s*변경)"
    r"(?:으로\s*이어진)?\s*(?:보도|기사|사실|문장|관찰|기록)"
)
_TARGET_CHANGE_DENIAL = re.compile(r"부인|사실(?:이|은)\s*아니|확인되지|하지\s*않")
_OWN_TARGET = re.compile(
    r"(?<![가-힣A-Za-z])(?:우리|당사|내|제|나|저|모델|AI)(?:의|가|는|은)?"
    r"(?=\s|$)\s*[^.;!?。\n]*$",
    re.IGNORECASE,
)
_RECOMMENDATION = re.compile(
    r"추천|권고|권유|권장|권(?:하|한|할|합|해|했)|제안|바랍|바란|바람|"
    r"(?:매수|매도|투자|진입|청산)\s*(?:적기|기회|타이밍)|"
    r"(?:매수|매도|투자|진입|청산)(?:의|할)?\s*시점|"
    r"(?:정답|최선의\s*전략|좋은\s*전략)"
)
_ACTION = r"(?:순매수|순매도|매수|매도|매입|매각|투자|진입|청산|보유|매집|처분|확대|축소)"
_ACTION_ADVICE_SUFFIX = (
    r"(?:를|을|에)?\s*(?:"
    r"해(?:야|라|보(?:자|세요))|하(?:여야|세요|십시오|자|라|도록)|"
    r"하(?:시)?(?:는|면)\s*(?:(?:것|편|게)(?:이|가|은)?\s*)?(?:좋|유리|낫|바람직)|"
    r"할\s*(?:필요|때|기회|적기|만)|해도\s*(?:좋|되)|"
    r"(?:가|는|이)?\s*(?:필요|유리|바람직|합리적)|"
    r"에\s*나서(?:야|자|세요)|"
    r"(?:검토|추가|확대|축소)(?:가|는|를|를\s*할)?\s*(?:필요|좋|유리))"
)
_ACTION_ADVICE = re.compile(_ACTION + _ACTION_ADVICE_SUFFIX)
_TARGET_ACTION_ADVICE = re.compile(r"(?:상향|하향|조정|산정|설정|제시)" + _ACTION_ADVICE_SUFFIX)
_DIRECTIVE = re.compile(
    r"담(?:아야|으세요|으십시오|자|아라)|"
    r"늘(?:려야|리세요|리십시오|리자|려라)|"
    r"줄(?:여야|이세요|이십시오|이자|여라)|"
    r"높(?:여야|이세요|이십시오|이자|여라)|"
    r"낮(?:춰야|추세요|추십시오|추자|춰라)|"
    r"키(?:워야|우세요|우십시오|우자|워라)|"
    r"(?<![가-힣])(?:사(?:야|세요|십시오|자)|팔(?:아야|으세요|자))|"
    r"(?:담|늘리|줄이|높이|낮추|키우|사|팔)(?:는|면)\s*"
    r"(?:(?:것|편|게)(?:이|가|은)?\s*)?(?:좋|유리|낫|바람직)"
)
# These nouns describe flows or their participants, rather than an instruction
# to trade. Match every occurrence separately; one noun never exempts a field.
_OBSERVATION_NOUN = re.compile(
    r"\s*(세|물량|여력|주체|기록|관찰|규모|금액|대금|동향|흐름|현황|내역|추이)"
)
_NET_OBSERVATION_END = re.compile(
    r"\s*(?:$|[,.;:!?。]|[0-9]|전환|지속|증가|감소|누적|상위|"
    r"(?:가|는|은|를|을|의|와|과|로|에|도|만)(?=\s|$)|"
    r"했|하였|됐|되었|되며|된|되고|된다)"
)
_OBSERVED_ACTOR = re.compile(
    r"(?:외국인|기관|개인)(?:\s*투자자)?(?:의|은|는|이|가)?\s*"
    r"(?:(?:대규모|집중|누적)\s*)?$"
)
_NOMINAL_END = re.compile(r"(?:$|[\s,.;:!?。]|(?:와|과|의|가|는|은|로|를|도|만|에)(?=\s|$))")


def _normalized(value: str) -> str:
    return unicodedata.normalize("NFKC", value)


def _price_targets(value: str) -> tuple[re.Match[str], ...]:
    # Bare "목표가" can also be "goal" plus the subject particle, as in
    # "양산 목표가 유지된다". Preserve that non-price reading.
    return tuple(
        match
        for match in _TARGET_NAME.finditer(value)
        if match.group() != "목표가"
        or _BARE_TARGET_PRICE.match(value, match.start())
        or _PRICE_BEFORE_TARGET.search(value[: match.start()])
    )


def _target_clause(value: str, end: int) -> str:
    # Do not borrow a later target occurrence or a later sentence's change.
    return re.split(
        r"(?<![0-9])\.|\.(?![0-9])|[;!?。\n]|목표\s*(?:주가|가격)|주가\s*목표|목표가",
        value[end:],
        maxsplit=1,
    )[0]


def _has_reported_target_change(source: str) -> bool:
    for match in _price_targets(source):
        clause = _target_clause(source, match.end())
        if _TARGET_COMPLETED_CHANGE.match(clause) and not _TARGET_CHANGE_DENIAL.search(clause):
            return True
    return False


def _is_target_change_observation(value: str, match: re.Match[str]) -> bool:
    clause = _target_clause(value, match.end())
    if (
        _OWN_TARGET.search(value[: match.start()])
        or _PRICE_BEFORE_TARGET.search(value[: match.start()])
        or _TARGET_NUMERIC_VALUE.match(clause)
        or _PRICE_AMOUNT.search(clause)
    ):
        return False
    return bool(_TARGET_CHANGE_REPORT.match(clause) or _TARGET_COMPLETED_CHANGE.match(clause))


def has_investment_advice(value: str, source: str = "") -> bool:
    """Return whether generated prose contains disallowed investment wording.

    ``source`` must contain only original sentences permitted for this field's
    selected claims, not claim summaries or the rest of the report. Without
    that context trading mentions retain the previous conservative rejection.
    Source support permits reporting a completed target-price change, but never
    excuses a recommendation or a target-price amount/proposal.
    Authenticated display quotations must be separated by the caller; quote
    marks or a provider-written ``원문:`` label confer no exemption here.
    """
    value, source = _normalized(value), _normalized(source)
    targets = _price_targets(value)
    trades = tuple(_TRADE.finditer(value))
    if not trades and not targets:
        return False
    if any(pattern.search(value) for pattern in (_RECOMMENDATION, _ACTION_ADVICE, _DIRECTIVE)):
        return True
    if targets and (
        _TARGET_ACTION_ADVICE.search(value)
        or _AMOUNT_PROPOSAL.search(value)
        or not _has_reported_target_change(source)
        or any(not _is_target_change_observation(value, match) for match in targets)
    ):
        return True
    for match in trades:
        trade = match.group()
        if match.start() > 0 and value[match.start() - 1] == "순":
            # Net purchases/sales are distinct from gross trading activity.
            # Source presence alone cannot authorize an arbitrary verb ending.
            if f"순{trade}" not in source or not (
                _OBSERVATION_NOUN.match(value, match.end())
                or _NET_OBSERVATION_END.match(value, match.end())
            ):
                return True
            continue
        noun = _OBSERVATION_NOUN.match(value, match.end())
        if noun is not None:
            if not re.search(re.escape(trade) + r"\s*" + re.escape(noun.group(1)), source):
                return True
            continue
        if (
            _OBSERVED_ACTOR.search(value[: match.start()])
            and _NOMINAL_END.match(value, match.end())
            and trade in source
        ):
            continue
        return True
    return False
