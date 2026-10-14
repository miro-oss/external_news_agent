"""Separate sourced trading observations from investment recommendations.

This is a conservative expression check, not a factual validator. Each trading
mention needs an observational form and corresponding selected source wording.
Existing guards still verify its actor, amount, timing and factual certainty.
Only callers can authenticate and remove server-rendered source quotations.
"""

import re
import unicodedata

from app.core.evidence import _CURRENCY_AMOUNT, _PREFIX_CURRENCY_AMOUNT, _companies
from app.llm.report_insight_guard import _STATE_QUOTATION, _asserted_state_alias

_TRADE = re.compile(r"매수|매도")
_TARGET_NAME = re.compile(r"목표\s*(?:주가|가격)|주가\s*목표|목표가")
_BARE_TARGET_PRICE = re.compile(
    r"(?:목표\s*(?:주가|가격)|목표가)(?:를|는|의|을|은)?\s*"
    r"(?::\s*)?(?:주당\s*)?"
    r"(?:[0-9]|[영일이삼사오육칠팔구십백천만억조]+(?:원|달러|엔)|"
    r"상향|하향|인상|인하|조정|변경|산정|설정|추천|제시|전망|높|낮)"
)
_TARGET_PARTICLE = r"(?:를|는|의|을|은|가|이)?\s*"
_PRICE_UNIT_END = (
    r"(?=$|[^가-힣]|(?:으로|로|을|를|의|은|는|이|가|에서|까지)(?=$|[^가-힣])|"
    r"(?:이다|입니다|이고|이며|이라는|이라고|이었다|이면|이지만)(?=$|[^가-힣])|"
    r"인(?=\s*(?:가격|금액|목표가))|"
    r"(?:대|선|쯤|짜리)(?:이다|입니다|이고|으로|로|의|은|는|이|가)?(?=$|[^가-힣]))"
)
_PRICE_AMOUNT = re.compile(
    r"(?:[₩$¥€]\s*[0-9][0-9,.]*|"
    r"(?:[0-9][0-9,.]*\s*(?:조|억|천|백|만)?|(?<![가-힣])[영일이삼사오육칠팔구십백천만억조]+)"
    r"\s*(?:(?:원|달러|엔|유로|퍼센트)" + _PRICE_UNIT_END + r"|%))"
)
_TARGET_MONEY_VALUE = re.compile(
    _TARGET_PARTICLE
    + r"(?::\s*)?(?:주당\s*)?(?:약\s*)?(?:"
    + _PRICE_AMOUNT.pattern
    + "|"
    + _PREFIX_CURRENCY_AMOUNT.pattern
    + ")",
    re.IGNORECASE,
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
    r"(?:(?:된|한)|으로\s*이어진)?\s*(?:보도|기사|사실|문장|관찰|기록)"
)
# The selected source authenticates the completed change. Output prose only
# needs to name that change; its analytical noun/verb is not an advice signal.
_TARGET_CHANGE_MENTION = re.compile(
    _TARGET_PARTICLE + r"(?:(?:상향|하향|인상|인하)(?:\s*조정)?|조정|변경|산정\s*근거\s*변경)"
    r"(?:된|한)?"
)
_TARGET_DIRECT_PROPOSAL = re.compile(
    r"(?:제시|설정|산정)\s*(?:한다|합니다|했다|했으며|하겠다)|"
    r"(?:상향|하향|인상|인하|조정|변경)(?:\s*조정)?\s*(?:한다|합니다|하겠다)"
)
_TARGET_CHANGE_DENIAL = re.compile(r"부인|사실(?:이|은)\s*아니|확인되지|하지\s*않")
_TARGET_UNASSERTED_SUFFIX = re.compile(
    r"[\"'”’」』\s]*(?:(?:다고|고|다는|는)?\s*(?:가정|전제)|"
    r"(?:다고|고|다는|는)?\s*"
    r"(?:(?:보도|기사|주장|사실|것|뜻|의미)(?:은|는|이|가|을|를)?\s*)?"
    r"(?:은|는|이|가|을|를)?\s*(?:허위|거짓|사실무근|아니|부인|부정|"
    r"볼\s*수\s*없|(?:확인|단정|확정|판단|인정)(?:할\s*수\s*없|하기\s*어렵)|"
    r"(?:확인|보도|발표|공개)(?:되|하)지\s*않))"
)
_TARGET_DIRECTION = re.compile(r"(?P<up>상향|인상|높였|올렸)|(?P<down>하향|인하|낮췄|내렸)")
_TARGET_CLAUSE_BREAK = re.compile(
    r"(?<![0-9])\.|\.(?![0-9])|[;!?。\n]|목표\s*(?:주가|가격)|주가\s*목표|목표가"
)
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
_NON_TRADING_RECOMMENDATION_OBJECT = re.compile(r"(?<![가-힣A-Za-z])(?:모니터링|관찰)(?:을|를)\s*$")
_COORDINATED_ACTION_END = re.compile(r"(?:와|과|및|·|,|and|&)\s*$", re.I)
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
_NET_AMOUNT_EXECUTION_ADVICE = re.compile(
    r"(?:는|은|가|이|를|을|도|만)?\s*(?:실행|진행)" + _ACTION_ADVICE_SUFFIX
)
_TARGET_ACTION_ADVICE = re.compile(
    r"(?:상향|하향|인상|인하|조정|변경|산정|설정|제시)(?:은)?" + _ACTION_ADVICE_SUFFIX
)
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
    r"\s*(세|물량|여력|주체|기록|관찰|규모|금액|대금|동향|흐름|유입|현황|내역|추이)"
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
_OBSERVATION_CLAUSE_BREAK = re.compile(r"(?<![0-9])\.|\.(?![0-9])|[,;!?。\n]")
_CONCENTRATION_PREDICATE = re.compile(
    r"(?:됐(?:다|고|으며|지만|는데)|되었(?:다|고|으며|지만|는데)|"
    r"되어|되며|되면서|되고|된다|된)(?=$|[\s,.;!?。])"
)
_CONCENTRATION_UNASSERTED = re.compile(
    r"만약|다면|라면|경우|가정|전제|전망|예상|예정|가능성|계획|"
    r"(?:하지|되지|있지|하지는|되지는|있지는)\s*않|아니|부인|철회|취소"
)
_CONCENTRATION_ADVICE = re.compile(
    r"좋|유리|필요|바람직|합리적|정답|전략|방법|방안|낫|"
    r"(?:^|\s)야(?:만)?(?=\s|하|한|합|$)"
)
_TRADING_ACTOR = re.compile(r"외국인|기관|개인")
_TRADING_MARKET = re.compile(r"유가증권시장|코스피|코스닥|krx|nxt", re.IGNORECASE)
_TARGET_TOKEN = re.compile(r"[가-힣A-Za-z0-9]+")
_TOKEN_PARTICLE = r"(?:에서|에도|에는|으로|의|와|과|은|는|이|가|을|를|에|로|도)?"
_ConcentrationContext = tuple[str, str, frozenset[str]]


def _net_amount_end(value: str, end: int) -> int | None:
    """Only a complete currency amount may intervene after a net-trade noun."""
    if value[end : end + 1] != "(":
        return None
    close = value.find(")", end + 1, end + 81)
    if close < 0:
        return None
    amount = value[end + 1 : close].strip()
    if (
        "\n" in amount
        or "\r" in amount
        or not any(
            pattern.fullmatch(amount) for pattern in (_CURRENCY_AMOUNT, _PREFIX_CURRENCY_AMOUNT)
        )
    ):
        return None
    return close + 1


def _trade_clause(value: str, match: re.Match) -> tuple[str, str]:
    before = _OBSERVATION_CLAUSE_BREAK.split(value[: match.start()])[-1]
    after = _OBSERVATION_CLAUSE_BREAK.split(value[match.end() :], maxsplit=1)[0]
    return before, after


def _target_tokens(value: str) -> frozenset[str]:
    return frozenset(
        re.sub(r"(?:와|과|의)$", "", token.casefold())
        for token in _TARGET_TOKEN.findall(value)
        if token not in {"등", "및"}
    )


def _contains_target(source: str, token: str) -> bool:
    return bool(
        re.search(
            r"(?<![가-힣A-Za-z0-9_])"
            + re.escape(token)
            + _TOKEN_PARTICLE
            + r"(?=$|[^가-힣A-Za-z0-9_])",
            source.casefold(),
        )
    )


def _concentration_context(value: str, match: re.Match) -> _ConcentrationContext | None:
    """Locate concentration within this trade's clause, before another trade."""
    before, after = _trade_clause(value, match)
    targets = frozenset()
    concentrated = re.match(r"[^\S\r\n]*집중", after)
    if concentrated is None:
        # A subject particle may precede an explicit target ending in 에/로.
        concentrated = re.match(
            r"(?:가|는|은|이)[^\S\r\n]*"
            r"(?:(?P<target>[^.;!?。\n]{1,80}?)(?:에|으로|로)[^\S\r\n]*)?집중",
            after,
        )
        if concentrated is not None and concentrated.group("target"):
            targets = _target_tokens(concentrated.group("target"))
    if concentrated is None or _TRADE.search(after[: concentrated.end()]):
        return None
    tail = after[concentrated.end() :]
    # A prior trade may introduce a separate observation ("순매도와 대형주
    # 중심의 매도 집중"); its earlier modifiers are not this trade's target.
    previous = tuple(_TRADE.finditer(before))
    target_prefix = before[previous[-1].end() :] if previous else before
    target_prefix = re.sub(r"^(?:와|과|및)\s*", "", target_prefix)
    target_prefix = target_prefix.removesuffix("순")
    target_prefix = _OBSERVED_ACTOR.sub("", target_prefix).strip()
    target = re.fullmatch(r"(.+?)(?:\s*중심(?:의|으로)?|으로|에|로)", target_prefix)
    if target is not None:
        targets |= _target_tokens(target.group(1))
    elif target_prefix:
        # An unmarked name ("현대차 매도 집중") is still a target, even if
        # the shared company detector does not recognize that proper noun.
        targets |= _target_tokens(target_prefix)
    return before + match.group() + after, tail, targets


def _concentration_observation(context: _ConcentrationContext, *, nominal: bool) -> bool:
    clause, tail, _ = context
    if _CONCENTRATION_UNASSERTED.search(clause):
        return False
    if _CONCENTRATION_ADVICE.search(tail):
        return False
    predicate = _CONCENTRATION_PREDICATE.match(tail)
    if predicate is not None:
        # A spaced imperative ("되어 야 한다") is not an observed event.
        return not re.match(r"\s*야(?:만)?", tail[predicate.end() :])
    return nominal and _NOMINAL_END.match(tail) is not None


def _has_source_concentration(
    source: str, trade: str, context: _ConcentrationContext, *, net: bool
) -> bool:
    companies = _companies(context[0].casefold())
    actors = set(_TRADING_ACTOR.findall(context[0]))
    for match in _TRADE.finditer(source):
        source_net = match.start() > 0 and source[match.start() - 1] == "순"
        observed = _concentration_context(source, match)
        if (
            match.group() == trade
            and source_net == net
            and observed is not None
            and len(_TRADE.findall(observed[0])) == 1
            and _concentration_observation(observed, nominal=False)
            and companies <= _companies(observed[0].casefold())
            and actors <= set(_TRADING_ACTOR.findall(observed[0]))
            and all(_contains_target(observed[0], token) for token in context[2])
        ):
            return True
    return False


def _has_source_net_subject(source: str, value: str, match: re.Match) -> bool:
    """The new parenthesized form cannot borrow another actor's net trade."""
    prefix, _ = _trade_clause(value, match)
    companies = _companies(prefix.casefold())
    actors = set(_TRADING_ACTOR.findall(prefix))
    markets = {item.casefold() for item in _TRADING_MARKET.findall(prefix)}
    for candidate in _TRADE.finditer(source):
        if (
            candidate.group() != match.group()
            or candidate.start() == 0
            or source[candidate.start() - 1] != "순"
        ):
            continue
        before, after = _trade_clause(source, candidate)
        clause = before + candidate.group() + after
        if (
            len(_TRADE.findall(clause)) == 1
            and companies <= _companies(clause.casefold())
            and actors <= set(_TRADING_ACTOR.findall(clause))
            and markets <= {item.casefold() for item in _TRADING_MARKET.findall(clause)}
        ):
            return True
    return False


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
        or _TARGET_MONEY_VALUE.match(value, match.end())
        or _PRICE_BEFORE_TARGET.search(value[: match.start()])
    )


def _target_clause(value: str, end: int) -> str:
    # Do not borrow a later target occurrence or a later sentence's change.
    # A quotation's punctuation cannot detach its own denial/reporting suffix.
    quotations = [match.span() for match in _STATE_QUOTATION.finditer(value)]
    for boundary in _TARGET_CLAUSE_BREAK.finditer(value, end):
        if any(left < boundary.start() < right for left, right in quotations):
            continue
        return value[end : boundary.start()]
    return value[end:]


def _target_change_is_asserted(change: re.Match[str], context: str) -> bool:
    suffix = context[change.end() :]
    suffix = re.sub(r"^[.。](?=[\"'”’」』])", "", suffix)
    return _asserted_state_alias(change, context) and not _TARGET_UNASSERTED_SUFFIX.match(suffix)


def _target_direction(value: str) -> str | None:
    # Later revenue/sales forecasts do not state this target's direction.
    change = (
        _TARGET_CHANGE_REPORT.match(value)
        or _TARGET_COMPLETED_CHANGE.match(value)
        or _TARGET_CHANGE_MENTION.match(value)
    )
    match = _TARGET_DIRECTION.search(change.group()) if change is not None else None
    return match.lastgroup if match is not None else None


def _has_reported_target_change(source: str, direction: str | None = None) -> bool:
    for match in _price_targets(source):
        clause = _target_clause(source, match.end())
        prefix = _OBSERVATION_CLAUSE_BREAK.split(source[: match.start()])[-1] + match.group()
        context = prefix + clause
        change = _TARGET_COMPLETED_CHANGE.match(context, len(prefix))
        if (
            change
            and not _TARGET_CHANGE_DENIAL.search(clause)
            and _target_change_is_asserted(change, context)
            and (direction is None or direction == _target_direction(change.group()))
        ):
            return True
    return False


def _is_target_change_observation(value: str, match: re.Match[str]) -> bool:
    clause = _target_clause(value, match.end())
    if (
        _OWN_TARGET.search(value[: match.start()])
        or _PRICE_BEFORE_TARGET.search(value[: match.start()])
        or _TARGET_NUMERIC_VALUE.match(clause)
        or _PRICE_AMOUNT.search(clause)
        or _TARGET_DIRECT_PROPOSAL.search(clause)
    ):
        return False
    change = (
        _TARGET_CHANGE_REPORT.match(clause)
        or _TARGET_COMPLETED_CHANGE.match(clause)
        or _TARGET_CHANGE_MENTION.match(clause)
    )
    return bool(change and _target_change_is_asserted(change, clause))


def _has_trading_recommendation(value: str) -> bool:
    for recommendation in _RECOMMENDATION.finditer(value):
        observation = _NON_TRADING_RECOMMENDATION_OBJECT.search(value[: recommendation.start()])
        # Exempt only this recommendation's non-trading object, never another
        # recommendation elsewhere or a coordinated trading action.
        if observation is None or _COORDINATED_ACTION_END.search(value[: observation.start()]):
            return True
    return False


def investment_policy_violation(value: str, source: str = "") -> str | None:
    """Return a closed diagnostic reason without changing policy acceptance.

    ``trading_observation_source`` means an observational trading form lacks
    its selected-source support. Every other rejection is ``investment_wording``;
    that reason takes priority if a field contains both types of violation.

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
        return None
    if _has_trading_recommendation(value) or any(
        pattern.search(value) for pattern in (_ACTION_ADVICE, _DIRECTIVE)
    ):
        return "investment_wording"
    # The check-only view closes the gap introduced by a currency parenthesis:
    # "순매도(금액)가 필요하다" must retain the ordinary action-advice veto.
    amount_spans = [
        (match.end(), end)
        for match in trades
        if match.start() > 0
        and value[match.start() - 1] == "순"
        and (end := _net_amount_end(value, match.end())) is not None
    ]
    advice_view = value
    for start, end in reversed(amount_spans):
        advice_view = advice_view[:start] + advice_view[end:]
    if _ACTION_ADVICE.search(advice_view):
        return "investment_wording"
    if targets and (
        _TARGET_ACTION_ADVICE.search(value)
        or _AMOUNT_PROPOSAL.search(value)
        or any(
            not _is_target_change_observation(value, match)
            or not _has_reported_target_change(
                source, _target_direction(_target_clause(value, match.end()))
            )
            for match in targets
        )
    ):
        return "investment_wording"
    source_missing = False
    for match in trades:
        trade = match.group()
        net = match.start() > 0 and value[match.start() - 1] == "순"
        concentration = _concentration_context(value, match)
        if concentration is not None:
            # A rejected concentration must not fall through to a generic
            # net-trade particle or actor allowance ("순매도가 집중해야 ...").
            if not _concentration_observation(concentration, nominal=True):
                return "investment_wording"
            if not _has_source_concentration(source, trade, concentration, net=net):
                source_missing = True
            continue
        if net:
            # Net purchases/sales are distinct from gross trading activity.
            # Source presence alone cannot authorize an arbitrary verb ending.
            if not (
                _OBSERVATION_NOUN.match(value, match.end())
                or _NET_OBSERVATION_END.match(value, match.end())
            ):
                amount_end = _net_amount_end(value, match.end())
                if (
                    amount_end is None
                    or _NET_AMOUNT_EXECUTION_ADVICE.match(value, amount_end)
                    or not (
                        _OBSERVATION_NOUN.match(value, amount_end)
                        or _NET_OBSERVATION_END.match(value, amount_end)
                    )
                ):
                    return "investment_wording"
                if not _has_source_net_subject(source, value, match):
                    source_missing = True
            if f"순{trade}" not in source:
                source_missing = True
            continue
        noun = _OBSERVATION_NOUN.match(value, match.end())
        if noun is not None:
            # "매수세" and "매수 흐름" describe the same observed flow.
            source_noun = (
                r"(?:세|흐름)" if noun.group(1) in {"세", "흐름"} else re.escape(noun.group(1))
            )
            if not re.search(re.escape(trade) + r"\s*" + source_noun, source):
                source_missing = True
            continue
        if _OBSERVED_ACTOR.search(value[: match.start()]) and _NOMINAL_END.match(
            value, match.end()
        ):
            if trade not in source:
                source_missing = True
            continue
        return "investment_wording"
    return "trading_observation_source" if source_missing else None


def has_investment_advice(value: str, source: str = "") -> bool:
    """Return the policy verdict; diagnostic classification grants no exemption."""
    return investment_policy_violation(value, source) is not None
