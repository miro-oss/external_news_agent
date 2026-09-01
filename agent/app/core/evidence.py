import re
import unicodedata
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

from app.schemas.evidence import EvidenceSentence

_NUMBER = re.compile(
    r"(?<![A-Za-z0-9])[-+]?\d[\d,]*(?:\.\d+)?(?![A-Za-z0-9])"
)
_DATE_NUMBER_UNIT = re.compile(r"\s*(년|월|일|분기)")
_QUANTITY_UNIT = re.compile(
    r"\s*(퍼센트|억원|만원|달러|조원|%|억|만|조|원|usd|배|개|건|명|톤|gb|tb)",
    re.IGNORECASE,
)
_CLAUSE_SEPARATOR = re.compile(
    r"(?:(?<!\d)[.!?](?!\d)|[。！？;；\n]+|,\s*|"
    r"(?:이고|이며|였고|했고|됐고)\s+)"
)
_NEGATION = re.compile(
    r"(?:\b(?:not|no|never|without)\b|않|아니|없|무산|취소|중단)",
    re.IGNORECASE,
)
_KOREAN_ORGANIZATION = re.compile(
    r"[A-Za-z가-힣][A-Za-z0-9가-힣&.-]{1,30}"
    r"(?:전자|하이닉스|반도체|디스플레이|테크놀로지|테크|그룹|홀딩스|은행|증권|공사|협회|위원회|연구원)"
)
_WORD = re.compile(r"[A-Za-z0-9가-힣]+")
_TECHNICAL_ANCHOR = re.compile(
    r"\b(?:[A-Z]{2,}[A-Z0-9]*|[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+)\b"
)
_AMBIGUOUS_RELATION = re.compile(
    r"(?:때문|따라서|영향|기여|유발|결과|전망|예상|가능성|목적|위해|"
    r"\b(?:because|therefore|due\s+to|lead(?:s|ing)?\s+to|caus(?:e|es|ed|ing)|"
    r"expected|likely|may|might|could|should)\b)",
    re.IGNORECASE,
)
_CONDITIONAL_MODALITY = re.compile(
    r"(?:가능성|전망|예정|예상|계획|(?:할|일)\s*수도|수\s*있(?:다|습니다|을)|"
    r"\b(?:may|might|could|possibly|likely)\b)",
    re.IGNORECASE,
)
_MODALITY_LADDER = (
    (
        6,
        "완료·양산",
        re.compile(
            r"(?:완료(?:했|됐|되었|하였|했다|됐다|함)|마쳤|완공|준공|"
            r"(?:양산|가동|출하)(?:을|를)?\s*(?:했|됐|되|한다|중|개시|돌입)|"
            r"\b(?:completed|finished|qualified|certified)\b)"
        ),
    ),
    (
        5,
        "착수·시작",
        re.compile(
            r"(?:착수|시작|개시|돌입|들어갔|들어갔다|"
            r"(?:확대|공급|제공|설치|건설)(?:했|한|한다|됐다|중)|"
            r"늘(?:렸|린다)|\b(?:started|began|launched|expanded|"
            r"supplied|provided|installed|shipped|delivered)\b)",
            re.IGNORECASE,
        ),
    ),
    (
        4,
        "승인·계약·확정",
        re.compile(
            r"(?:승인|체결|확정|결정|합의|"
            r"\b(?:approved|signed|confirmed|decided|contracted)\b)",
            re.IGNORECASE,
        ),
    ),
    (
        3,
        "발표·공식화",
        re.compile(r"(?:발표|공식화|공표|공개|\b(?:announced|published)\b)", re.IGNORECASE),
    ),
    (
        2,
        "계획·추진",
        re.compile(r"(?:계획|추진|준비|\b(?:planned|plans?|preparing)\b)", re.IGNORECASE),
    ),
    (
        1,
        "검토·논의",
        re.compile(
            r"(?:검토|논의|협의|고려|\b(?:reviewing|discussing|considering)\b)",
            re.IGNORECASE,
        ),
    ),
    (
        0,
        "관측·보도",
        re.compile(r"(?:관측|보도|언급|\b(?:observed|reported|mentioned)\b)", re.IGNORECASE),
    ),
)
_BILINGUAL_DIRECT_RELATIONS = (
    (
        re.compile(r"(?:계약.{0,20}체결|체결.{0,20}계약)"),
        re.compile(
            r"\b(?:signed|entered)\b.{0,80}\b(?:agreement|contract)\b|"
            r"\b(?:agreement|contract)\b.{0,80}\b(?:signed|entered)\b",
            re.IGNORECASE,
        ),
    ),
    (
        re.compile(r"(?:설치.{0,15}예정|설치할)"),
        re.compile(
            r"\b(?:scheduled|plans?|planned|will)\b.{0,80}"
            r"\b(?:install|installation)\b|"
            r"\binstallation\b.{0,80}\b(?:scheduled|planned)\b",
            re.IGNORECASE,
        ),
    ),
)
_DIRECT_RULE_GROUNDED_OVERLAP = 0.8

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
_KNOWN_COMPANY_ALIASES = frozenset(
    re.sub(r"\s+", " ", unicodedata.normalize("NFKC", alias).casefold()).strip()
    for aliases in _COMPANY_ALIASES.values()
    for alias in aliases
)


@dataclass(frozen=True, slots=True)
class RuleAssessment:
    status: str
    accepted_sentence_ids: list[int]
    reason: str


@dataclass(frozen=True, slots=True)
class ModalityAssessment:
    claim_stage: int
    evidence_stage: int
    claim_term: str
    evidence_term: str

    @property
    def difference(self) -> int:
        return self.claim_stage - self.evidence_stage

    @property
    def reason(self) -> str:
        return (
            f"근거는 '{self.evidence_term}' 단계인데 주장은 "
            f"'{self.claim_term}' 단계입니다."
        )


@dataclass(frozen=True, slots=True)
class CrossSourceSignal:
    extra_numbers: frozenset[str]
    extra_companies: frozenset[str]
    polarity_mismatch: bool
    number_mismatch: bool

    @property
    def promotion_eligible(self) -> bool:
        return bool(
            self.extra_numbers
            or self.extra_companies
            or self.polarity_mismatch
            or self.number_mismatch
        )

    @property
    def stance(self) -> str:
        if self.polarity_mismatch or self.number_mismatch:
            return "DISPUTES"
        if self.extra_numbers or self.extra_companies:
            return "ADDS"
        return "SUPPORTS"

    @property
    def confidence(self) -> float:
        if self.polarity_mismatch or self.number_mismatch:
            return 0.85
        if self.extra_numbers or self.extra_companies:
            return 0.65
        return 0.55


def cross_source_signal(reference_text: str, candidate_text: str) -> CrossSourceSignal:
    """제목·요약만으로 승격 사전 컷과 RULE stance 후보를 결정한다."""
    reference = _normalize(reference_text)
    candidate = _normalize(candidate_text)
    reference_numbers = _numbers(reference)
    candidate_numbers = _numbers(candidate)
    return CrossSourceSignal(
        extra_numbers=frozenset(candidate_numbers - reference_numbers),
        extra_companies=frozenset(_companies(candidate) - _companies(reference)),
        polarity_mismatch=_polarity_mismatch(candidate, reference),
        number_mismatch=bool(
            reference_numbers
            and candidate_numbers
            and reference_numbers != candidate_numbers
        ),
    )


def factual_mismatches(claim: str, evidence_text: str) -> list[str]:
    """근거에 문자 그대로 존재해야 하는 사실만 보수적으로 검사한다."""
    normalized_claim = _normalize(claim)
    normalized_evidence = _normalize(evidence_text)
    mismatches: list[str] = []

    missing_numbers = _numbers(normalized_claim) - _numbers(normalized_evidence)
    if missing_numbers:
        mismatches.append("근거에서 확인되지 않는 숫자: " + ", ".join(sorted(missing_numbers)))

    contextual_numbers = _contextual_number_mismatches(
        normalized_claim, normalized_evidence
    )
    if contextual_numbers:
        mismatches.append(
            "근거와 연결이 다른 숫자: " + ", ".join(contextual_numbers)
        )

    missing_dates = _date_terms(normalized_claim) - _date_terms(normalized_evidence)
    if missing_dates:
        mismatches.append("근거와 일치하지 않는 날짜 표현: " + ", ".join(sorted(missing_dates)))

    claim_companies = _companies(normalized_claim)
    evidence_companies = _companies(normalized_evidence)
    missing_companies = claim_companies - evidence_companies
    if missing_companies:
        mismatches.append("근거에서 확인되지 않는 기업명: " + ", ".join(sorted(missing_companies)))

    if _polarity_mismatch(normalized_claim, normalized_evidence):
        mismatches.append("근거와 반대되는 부정 표현이 포함되어 있습니다.")
    modality = modality_overreach(claim, evidence_text)
    if modality is not None and modality.difference >= 2:
        mismatches.append(modality.reason)
    return mismatches


def modality_overreach(claim: str, evidence_text: str) -> ModalityAssessment | None:
    """표현의 확정 단계가 근거보다 강한 경우 단계와 설명을 반환한다."""
    claim_stage, claim_term = _modality_stage(claim)
    evidence_stage, evidence_term = _modality_stage(evidence_text)
    if claim_stage <= evidence_stage:
        return None
    return ModalityAssessment(
        claim_stage=claim_stage,
        evidence_stage=evidence_stage,
        claim_term=claim_term,
        evidence_term=evidence_term,
    )


def has_forecast_qualifier(value: str) -> bool:
    return _CONDITIONAL_MODALITY.search(_normalize(value)) is not None or any(
        marker in _normalize(value)
        for marker in ("예상", "예정", "계획", "목표", "전망")
    )


def assess_with_rules(
    claim: str,
    sentences: list[EvidenceSentence],
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> RuleAssessment:
    evidence_text = "\n".join(sentence.text for sentence in sentences)
    mismatches = factual_mismatches(claim, evidence_text)
    if mismatches:
        return RuleAssessment("ungrounded", [], "; ".join(mismatches))

    claim_tokens = _tokens(claim)
    if not claim_tokens:
        return RuleAssessment("ungrounded", [], "검증할 수 있는 주장 토큰이 없습니다.")

    candidates: list[tuple[EvidenceSentence, set[str], float]] = []
    for sentence in sentences:
        shared = claim_tokens & _tokens(sentence.text)
        overlap = len(shared) / len(claim_tokens)
        if overlap >= weak_overlap:
            candidates.append((sentence, shared, overlap))

    accepted: list[EvidenceSentence] = []
    covered_tokens: set[str] = set()
    for sentence, shared, _ in sorted(
        candidates, key=lambda candidate: candidate[2], reverse=True
    ):
        if shared - covered_tokens:
            accepted.append(sentence)
            covered_tokens.update(shared)
    accepted.sort(key=lambda sentence: sentences.index(sentence))
    if not accepted:
        return RuleAssessment("ungrounded", [], "주장과 직접 연결되는 근거 문장이 없습니다.")

    combined_tokens = _tokens("\n".join(sentence.text for sentence in accepted))
    coverage = len(claim_tokens & combined_tokens) / len(claim_tokens)
    modality = modality_overreach(claim, evidence_text)
    status = (
        "weak"
        if modality is not None and modality.difference == 1
        else "grounded" if coverage >= grounded_overlap else "weak"
    )
    reason = (
        "주장의 핵심 표현과 사실값이 근거 문장에서 확인됩니다."
        if status == "grounded"
        else modality.reason
        if modality is not None and modality.difference == 1
        else "일부 표현은 연결되지만 직접 근거가 충분하지 않습니다."
    )
    return RuleAssessment(status, [sentence.id for sentence in accepted], reason)


def assess_with_decisive_rules(
    claim: str,
    sentences: list[EvidenceSentence],
    *,
    grounded_overlap: float,
) -> RuleAssessment | None:
    """Provider 없이 확정해도 안전한 불일치 또는 직접 근거만 반환한다."""
    evidence_text = "\n".join(sentence.text for sentence in sentences)
    mismatches = factual_mismatches(claim, evidence_text)
    if mismatches:
        return RuleAssessment("ungrounded", [], "; ".join(mismatches))

    modality = modality_overreach(claim, evidence_text)
    if modality is not None and modality.difference == 1:
        return RuleAssessment(
            "weak",
            [sentence.id for sentence in sentences],
            modality.reason,
        )

    claim_tokens = _tokens(claim)
    if not claim_tokens:
        return None

    required_overlap = max(grounded_overlap, _DIRECT_RULE_GROUNDED_OVERLAP)
    normalized_claim = _normalize(claim)
    ambiguous = len(_clauses(claim)) > 1 or _AMBIGUOUS_RELATION.search(claim) is not None
    candidates: list[tuple[EvidenceSentence, float, str]] = []
    for sentence in sentences:
        if factual_mismatches(claim, sentence.text):
            continue
        sentence_tokens = _tokens(sentence.text)
        overlap = len(claim_tokens & sentence_tokens) / len(claim_tokens)
        directly_contained = normalized_claim in _normalize(sentence.text)
        if directly_contained or (overlap >= required_overlap and not ambiguous):
            candidates.append((sentence, overlap, "direct"))
            continue
        if _bilingual_direct_match(claim, sentence.text):
            candidates.append((sentence, 1.0, "bilingual"))

    if not candidates:
        return None
    sentence, _, match_type = max(candidates, key=lambda candidate: candidate[1])
    reason = (
        "단일 근거 문장에서 주장의 핵심 표현과 사실값이 직접 확인됩니다."
        if match_type == "direct"
        else "단일 근거 문장에서 한영 동치 관계와 사실값이 직접 확인됩니다."
    )
    return RuleAssessment("grounded", [sentence.id], reason)


def _normalize(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    return re.sub(r"[^\S\n]+", " ", normalized).strip()


def _modality_stage(value: str) -> tuple[int, str]:
    normalized = _normalize(value)
    clause_stages: list[tuple[int, str]] = []
    for clause in _clauses(normalized):
        normalized_tokens = " ".join(
            _strip_korean_suffix(match.group()) for match in _WORD.finditer(clause)
        )
        searchable = f"{clause} {normalized_tokens}"
        conditional = _CONDITIONAL_MODALITY.search(searchable)
        if conditional is not None:
            clause_stages.append((0, conditional.group().strip()))
            continue
        if _has_negation(searchable):
            clause_stages.append((0, "부정 표현"))
            continue
        for stage, label, pattern in _MODALITY_LADDER:
            match = pattern.search(searchable)
            if match is not None:
                clause_stages.append((stage, match.group().strip() or label))
                break
        else:
            clause_stages.append((0, "관측·보도"))
    return max(clause_stages, key=lambda value: value[0], default=(0, "관측·보도"))


def _bilingual_direct_match(claim: str, evidence: str) -> bool:
    if bool(re.search(r"[가-힣]", claim)) == bool(re.search(r"[가-힣]", evidence)):
        return False
    korean_text, english_text = (
        (claim, evidence) if re.search(r"[가-힣]", claim) else (evidence, claim)
    )
    relation_matches = any(
        korean.search(korean_text) is not None and english.search(english_text) is not None
        for korean, english in _BILINGUAL_DIRECT_RELATIONS
    )
    if not relation_matches:
        return False
    claim_anchors = _stable_anchors(claim)
    evidence_anchors = _stable_anchors(evidence)
    distinct_claim_anchors = {
        anchor.partition(":")[2] for anchor in claim_anchors
    }
    return len(distinct_claim_anchors) >= 2 and claim_anchors <= evidence_anchors


def _stable_anchors(value: str) -> set[str]:
    anchors = {f"number:{number}" for number in _numbers(_normalize(value))}
    anchors.update(f"company:{company.casefold()}" for company in _companies(_normalize(value)))
    anchors.update(
        f"term:{match.group().casefold()}" for match in _TECHNICAL_ANCHOR.finditer(value)
    )
    return anchors


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
    companies.update(
        match.group()
        for match in _KOREAN_ORGANIZATION.finditer(value)
        if _normalize(match.group()) not in _KNOWN_COMPANY_ALIASES
    )
    return companies


def _contains_alias(value: str, alias: str) -> bool:
    for match in re.finditer(re.escape(alias), value):
        if match.start() > 0 and value[match.start() - 1].isalnum():
            continue
        token_end = match.end()
        while token_end < len(value) and value[token_end].isalnum():
            token_end += 1
        suffix = value[match.end():token_end]
        if not suffix or _is_korean_suffix_chain(suffix):
            return True
    return False


def _is_korean_suffix_chain(value: str) -> bool:
    remaining = value
    while remaining:
        suffix = next(
            (candidate for candidate in _KOREAN_SUFFIXES if remaining.startswith(candidate)),
            None,
        )
        if suffix is None:
            return False
        remaining = remaining[len(suffix):]
    return True


def _contextual_number_mismatches(claim: str, evidence: str) -> list[str]:
    claim_facts = _numeric_facts(claim)
    evidence_facts = _numeric_facts(evidence)
    mismatches: list[str] = []
    for anchors, values in claim_facts:
        matching_contexts = [
            evidence_values
            for evidence_anchors, evidence_values in evidence_facts
            if anchors <= evidence_anchors
        ]
        if matching_contexts and any(values <= candidate for candidate in matching_contexts):
            continue
        if matching_contexts:
            mismatches.append(
                f"{'/'.join(sorted(anchors))}→{'/'.join(sorted(values))}"
            )
    return list(dict.fromkeys(mismatches))


def _numeric_facts(value: str) -> list[tuple[frozenset[str], frozenset[str]]]:
    facts: list[tuple[frozenset[str], frozenset[str]]] = []
    for clause in _clauses(value):
        anchors = frozenset(_date_number_anchors(clause) | _date_terms(clause) | _companies(clause))
        values = frozenset(_quantity_values(clause))
        if anchors and values:
            facts.append((anchors, values))
    return facts


def _date_number_anchors(value: str) -> set[str]:
    anchors: set[str] = set()
    for match in _NUMBER.finditer(value):
        unit_match = _DATE_NUMBER_UNIT.match(value, match.end())
        if unit_match is None:
            continue
        number = _normalized_number(match.group())
        if number is not None:
            anchors.add(number + unit_match.group(1))
    return anchors


def _quantity_values(value: str) -> set[str]:
    quantities: set[str] = set()
    for match in _NUMBER.finditer(value):
        if _DATE_NUMBER_UNIT.match(value, match.end()) is not None:
            continue
        number = _normalized_number(match.group())
        if number is None:
            continue
        unit_match = _QUANTITY_UNIT.match(value, match.end())
        unit = unit_match.group(1).casefold() if unit_match is not None else ""
        quantities.add(number + unit)
    return quantities


def _normalized_number(raw: str) -> str | None:
    try:
        number = Decimal(raw.replace(",", ""))
    except InvalidOperation:
        return None
    return format(number.normalize(), "f")


def _polarity_mismatch(claim: str, evidence: str) -> bool:
    evidence_clauses = _clauses(evidence)
    if not evidence_clauses:
        return False
    for claim_clause in _clauses(claim):
        claim_tokens = _tokens(claim_clause)
        candidates = [
            evidence_clause
            for evidence_clause in evidence_clauses
            if not claim_tokens or claim_tokens & _tokens(evidence_clause)
        ]
        if candidates and not any(
            _has_negation(claim_clause) == _has_negation(candidate)
            for candidate in candidates
        ):
            return True
    return False


def _has_negation(value: str) -> bool:
    return _NEGATION.search(value) is not None


def _clauses(value: str) -> list[str]:
    return [clause.strip() for clause in _CLAUSE_SEPARATOR.split(value) if clause.strip()]


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
