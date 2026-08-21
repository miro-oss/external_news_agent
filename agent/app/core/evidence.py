import re
import unicodedata
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

from app.schemas.evidence import EvidenceSentence

_NUMBER = re.compile(
    r"(?<![A-Za-z0-9])[-+]?\d[\d,]*(?:\.\d+)?(?![A-Za-z0-9])"
)
_KOREAN_ORGANIZATION = re.compile(
    r"[A-Za-z가-힣][A-Za-z0-9가-힣&.-]{1,30}"
    r"(?:전자|하이닉스|반도체|디스플레이|테크놀로지|테크|그룹|홀딩스|은행|증권|공사|협회|위원회|연구원)"
)
_WORD = re.compile(r"[A-Za-z0-9가-힣]+")

_DATE_TERM_ALIASES = {
    "오늘": "오늘",
    "내일": "내일",
    "올해": "올해",
    "금년": "올해",
    "내년": "내년",
    "명년": "내년",
    "지난해": "지난해",
    "작년": "지난해",
    "상반기": "상반기",
    "하반기": "하반기",
}
_COMPANY_ALIASES = {
    "삼성전자": ("삼성전자", "samsung electronics"),
    "SK하이닉스": ("sk하이닉스", "sk hynix"),
    "TSMC": ("tsmc", "대만반도체"),
    "엔비디아": ("엔비디아", "nvidia"),
    "AMD": ("amd",),
    "인텔": ("인텔", "intel"),
    "마이크론": ("마이크론", "micron"),
    "브로드컴": ("브로드컴", "broadcom"),
    "퀄컴": ("퀄컴", "qualcomm"),
    "ASML": ("asml",),
    "도쿄일렉트론": ("도쿄일렉트론", "tokyo electron"),
    "애플": ("애플", "apple"),
    "마이크로소프트": ("마이크로소프트", "microsoft"),
    "구글": ("구글", "google", "alphabet"),
    "아마존": ("아마존", "amazon"),
    "메타": ("메타", "meta platforms"),
    "OpenAI": ("openai",),
    "Anthropic": ("anthropic", "앤트로픽"),
    "Arm": ("arm holdings", "arm홀딩스"),
}
_STOP_WORDS = frozenset(
    {
        "그리고",
        "그러나",
        "대한",
        "위한",
        "있다",
        "없다",
        "한다",
        "된다",
        "것이다",
        "the",
        "and",
        "for",
        "from",
        "that",
        "this",
        "with",
    }
)
_KOREAN_SUFFIXES = (
    "으로",
    "에서",
    "에게",
    "까지",
    "부터",
    "보다",
    "이라고",
    "라는",
    "은",
    "는",
    "이",
    "가",
    "을",
    "를",
    "의",
    "에",
    "로",
    "과",
    "와",
    "도",
    "만",
)


@dataclass(frozen=True, slots=True)
class RuleAssessment:
    status: str
    accepted_sentence_ids: list[int]
    reason: str


def factual_mismatches(claim: str, evidence_text: str) -> list[str]:
    """근거에 문자 그대로 존재해야 하는 사실만 보수적으로 검사한다."""
    normalized_claim = _normalize(claim)
    normalized_evidence = _normalize(evidence_text)
    mismatches: list[str] = []

    missing_numbers = _numbers(normalized_claim) - _numbers(normalized_evidence)
    if missing_numbers:
        mismatches.append("근거에서 확인되지 않는 숫자: " + ", ".join(sorted(missing_numbers)))

    missing_dates = _date_terms(normalized_claim) - _date_terms(normalized_evidence)
    if missing_dates:
        mismatches.append("근거와 일치하지 않는 날짜 표현: " + ", ".join(sorted(missing_dates)))

    claim_companies = _companies(normalized_claim)
    evidence_companies = _companies(normalized_evidence)
    missing_companies = claim_companies - evidence_companies
    if missing_companies:
        mismatches.append("근거에서 확인되지 않는 기업명: " + ", ".join(sorted(missing_companies)))
    return mismatches


def assess_with_rules(
    claim: str,
    sentences: list[EvidenceSentence],
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> RuleAssessment:
    evidence_text = " ".join(sentence.text for sentence in sentences)
    mismatches = factual_mismatches(claim, evidence_text)
    if mismatches:
        return RuleAssessment("ungrounded", [], "; ".join(mismatches))

    claim_tokens = _tokens(claim)
    if not claim_tokens:
        return RuleAssessment("ungrounded", [], "검증할 수 있는 주장 토큰이 없습니다.")

    accepted: list[EvidenceSentence] = []
    for sentence in sentences:
        overlap = len(claim_tokens & _tokens(sentence.text)) / len(claim_tokens)
        if overlap >= weak_overlap:
            accepted.append(sentence)
    if not accepted:
        return RuleAssessment("ungrounded", [], "주장과 직접 연결되는 근거 문장이 없습니다.")

    combined_tokens = _tokens(" ".join(sentence.text for sentence in accepted))
    coverage = len(claim_tokens & combined_tokens) / len(claim_tokens)
    status = "grounded" if coverage >= grounded_overlap else "weak"
    reason = (
        "주장의 핵심 표현과 사실값이 근거 문장에서 확인됩니다."
        if status == "grounded"
        else "일부 표현은 연결되지만 직접 근거가 충분하지 않습니다."
    )
    return RuleAssessment(status, [sentence.id for sentence in accepted], reason)


def _normalize(value: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value).casefold()).strip()


def _numbers(value: str) -> set[str]:
    normalized: set[str] = set()
    for match in _NUMBER.finditer(value):
        raw = match.group().replace(",", "")
        try:
            number = Decimal(raw)
        except InvalidOperation:
            continue
        normalized.add(format(number.normalize(), "f"))
    return normalized


def _date_terms(value: str) -> set[str]:
    return {
        canonical
        for term, canonical in _DATE_TERM_ALIASES.items()
        if term in value
    }


def _companies(value: str) -> set[str]:
    companies = {
        canonical
        for canonical, aliases in _COMPANY_ALIASES.items()
        if any(_contains_alias(value, alias.casefold()) for alias in aliases)
    }
    known_aliases = {
        _normalize(alias)
        for aliases in _COMPANY_ALIASES.values()
        for alias in aliases
    }
    companies.update(
        match.group()
        for match in _KOREAN_ORGANIZATION.finditer(value)
        if _normalize(match.group()) not in known_aliases
    )
    return companies


def _contains_alias(value: str, alias: str) -> bool:
    if re.fullmatch(r"[a-z0-9 ]+", alias):
        return re.search(rf"(?<![a-z0-9]){re.escape(alias)}(?![a-z0-9])", value) is not None
    return alias in value


def _tokens(value: str) -> set[str]:
    tokens: set[str] = set()
    for match in _WORD.finditer(_normalize(value)):
        token = _strip_korean_suffix(match.group())
        if len(token) >= 2 and token not in _STOP_WORDS:
            tokens.add(token)
    return tokens


def _strip_korean_suffix(token: str) -> str:
    for suffix in _KOREAN_SUFFIXES:
        if token.endswith(suffix) and len(token) - len(suffix) >= 2:
            return token[: -len(suffix)]
    return token
