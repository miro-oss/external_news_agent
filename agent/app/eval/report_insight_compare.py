"""Build an offline, blind human review from saved report-insight run results.

This module never calls a model or starts a server. The HTML contains normalized
source/output text and opaque review identifiers, while the separate private key
contains variant identities, attempt identifiers, and provenance hashes. Human
ratings do not turn generation failures into successful quality comparisons.
"""

import argparse
import hashlib
import html
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from pydantic import ValidationError

from app.eval.report_insight_review import QUALITY_RUBRIC
from app.llm.report_insight_service import importance_grade, importance_score
from app.schemas.report_insight import (
    ReportAudienceInsight,
    ReportInsightOutput,
    ReportInsightRequest,
)

_VARIANTS = ("single_call", "staged")
_AUDIENCES = {
    "CHIP_MAKER": "반도체 제조사",
    "EQUIPMENT_MAKER": "장비·소재 기업",
    "MARKET_INVESTOR": "시장 투자자",
    "IT_INFRA": "IT 인프라 운영자",
}
_CRITERIA = {
    "factual_integrity": "사실·귀속 보존",
    "audience_fit": "관점 업무 적합성",
    "importance_calibration": "중요도·시급성 판단",
    "report_synthesis": "리포트 종합·상충 처리",
    "conditional_mechanism": "조건·경로·반증",
    "actionable_watch": "구체적 확인 항목",
}
_GRADES = {"high": "높음", "medium": "보통", "low": "낮음", "unavailable": "판단 보류"}
_STATUS = {
    "success": "생성 완료",
    "failed": "생성 실패",
    "pending": "생성 대기 / 미완료",
    "missing": "결과 없음",
    "invalid": "형식·근거·비교 입력 검증 실패",
}
_PAIR_STATUS = {
    "eligible": "평가 가능",
    "failed": "생성 실패 · 미채점",
    "incomplete": "생성 미완료 · 미채점",
    "invalid": "검증 실패 · 미채점",
}


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _digest(value: Any) -> str:
    return hashlib.sha256(_canonical(value).encode("utf-8")).hexdigest()


def _escape(value: Any) -> str:
    return html.escape(str(value), quote=True)


def _script_json(value: Any) -> str:
    # JSON lives in a script text node: </script> and Unicode line separators
    # must remain data, even when every input string is adversarial.
    return (
        _canonical(value)
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    )


def _source_anchor(review_id: str, claim_id: str) -> str:
    return "source-" + review_id + "-" + _digest(claim_id)[:12]


def _refs(review_id: str, claim_ids: list[str]) -> str:
    links = [
        f'<a href="#{_source_anchor(review_id, claim_id)}">{_escape(claim_id)}</a>'
        for claim_id in claim_ids
    ]
    return '<p class="refs">근거: ' + (" · ".join(links) or "없음") + "</p>"


def _paragraph(label: str, value: Any) -> str:
    return f'<p class="prose"><strong>{_escape(label)}</strong> {_escape(value)}</p>'


def _request_content(request: ReportInsightRequest) -> dict:
    # Idempotency keys can differ across attempts. Compared evidence must not.
    return {
        "audiences": request.audiences,
        "report": request.report.model_dump(by_alias=True, mode="json"),
        "findings": [item.model_dump(by_alias=True, mode="json") for item in request.findings],
    }


def _validated_candidate(
    entry: dict | None, request: ReportInsightRequest | None
) -> tuple[str, ReportInsightOutput | None]:
    if entry is None:
        return "missing", None
    status = entry.get("status")
    if status in ("failed", "pending"):
        return status, None
    if status != "success" or request is None:
        return "invalid", None
    try:
        response = entry.get("response")
        if not isinstance(response, dict):
            return "invalid", None
        # Runner responses include meta; the paired presentation deliberately
        # discards model, attempt, usage, and latency metadata.
        output = ReportInsightOutput.model_validate({"insights": response.get("insights")})
        audiences = [item.audience for item in output.insights]
        if len(audiences) != len(set(audiences)) or set(audiences) != set(request.audiences):
            return "invalid", None
        known = {finding.id: {claim.id for claim in finding.claims} for finding in request.findings}
        all_claims = set().union(*known.values())
        for insight in output.insights:
            ids = [item.finding_id for item in insight.assessments]
            if len(ids) != len(set(ids)) or set(ids) != set(known):
                return "invalid", None
            for item in insight.assessments:
                if not set(item.basis_claim_ids) <= known[item.finding_id]:
                    return "invalid", None
            for item in [*insight.overview, *insight.implications, *insight.watch_items]:
                if not set(item.basis_claim_ids) <= all_claims:
                    return "invalid", None
    except (ValueError, TypeError, ValidationError):
        return "invalid", None
    return "success", output


def _render_sources(request: ReportInsightRequest | None, review_id: str) -> str:
    if request is None:
        return '<p class="empty">원문 입력을 검증하지 못해 평가할 수 없습니다.</p>'
    sections = []
    for finding in request.findings:
        try:
            parsed = urlsplit(finding.canonical_url)
        except ValueError:
            parsed = urlsplit("")
        link = (
            f'<a href="{_escape(finding.canonical_url)}" target="_blank" '
            'rel="noreferrer noopener">원문 기사 ↗</a>'
            if parsed.scheme.casefold() in ("http", "https") and parsed.netloc
            else '<span class="muted">원문 링크 형식 미지원</span>'
        )
        claims = []
        for claim in finding.claims:
            attribution = f" · 발언자 {_escape(claim.attributed_to)}" if claim.attributed_to else ""
            indices = ", ".join(str(index) for index in claim.evidence_sentence_ids)
            claims.append(
                f'<div class="source-claim" id="{_source_anchor(review_id, claim.id)}">'
                f'<p class="meta">{_escape(claim.id)} · {_escape(claim.claim_type)}{attribution}'
                f' · 문장 {indices}</p><p class="prose">{_escape(claim.text)}</p></div>'
            )
        sentences = "".join(
            f'<li value="{sentence.index}"><span class="meta">문장 {sentence.index}</span>'
            f'<p class="prose">{_escape(sentence.text)}</p></li>'
            for sentence in finding.sentences
        )
        sections.append(
            '<section class="source-article">'
            f"<h3>{_escape(finding.article_title)}</h3>"
            f'<p class="meta">이슈 {finding.id} · 기사 {finding.article_id} · '
            f"기사 날짜 {_escape(finding.published_at or '미제공')}</p>{link}"
            + "".join(claims)
            + '<details open><summary>원문 문장</summary><ol class="sentences" start="0">'
            + f"{sentences}</ol></details></section>"
        )
    return "".join(sections)


def _render_insight(
    insight: ReportAudienceInsight, request: ReportInsightRequest, review_id: str
) -> str:
    snapshot_order = {finding.id: index for index, finding in enumerate(request.findings)}
    ordered = sorted(
        insight.assessments,
        key=lambda item: (
            importance_score(item.axes) is None,
            -(importance_score(item.axes) or 0),
            snapshot_order[item.finding_id],
        ),
    )
    overall = _GRADES[importance_grade(ordered[0].axes)]
    parts = [
        f'<h3 class="headline">{_escape(insight.headline)}</h3>',
        f'<p class="meta">최고 이슈 중요도: <strong>{overall}</strong> · '
        f"이슈 {len(ordered)}개 평가</p>",
        "<h4>리포트 요약</h4>",
    ]
    for item in insight.overview:
        parts.append(
            '<section class="output-item">'
            + _paragraph("", item.text)
            + _paragraph("전제", item.assumption)
            + _refs(review_id, item.basis_claim_ids)
            + "</section>"
        )
    if not insight.overview:
        parts.append('<p class="empty">종합 요약 없음</p>')
    parts.append(
        '<h4>이슈 중요도와 근거</h4><p class="meta">상위 이슈부터 표시 · 판단 보류는 뒤에 표시</p>'
    )
    for position, item in enumerate(ordered, 1):
        score = importance_score(item.axes)
        score_label = "판단 보류" if score is None else f"{score:.2f}/3"
        axes = " · ".join(
            f"{label} "
            f"{getattr(item.axes, axis) if getattr(item.axes, axis) is not None else '판단 보류'}"
            for axis, label in (("directness", "관련성"), ("impact", "영향"), ("urgency", "시급성"))
        )
        parts.append(
            '<section class="output-item">'
            f"<h5>{position}. 이슈 {item.finding_id} · {_GRADES[importance_grade(item.axes)]}</h5>"
            f'<p class="meta">{score_label} · {axes}</p>'
            + _paragraph("판정 이유", item.reason)
            + _refs(review_id, item.basis_claim_ids)
            + "</section>"
        )
    parts.append("<h4>조건부 해석</h4>")
    for item in insight.implications:
        parts.append(
            '<section class="output-item">'
            + _paragraph("해석", item.text)
            + _paragraph("영향 경로", item.mechanism)
            + _paragraph("성립 조건", item.assumption)
            + _paragraph("해석을 철회할 관측", item.falsified_by)
            + _refs(review_id, item.basis_claim_ids)
            + "</section>"
        )
    if not insight.implications:
        parts.append('<p class="empty">조건부 해석 없음</p>')
    parts.append("<h4>확인할 항목</h4>")
    for item in insight.watch_items:
        parts.append(
            '<section class="output-item">'
            + _paragraph("주제", item.topic)
            + _paragraph("확인 지표", item.indicator)
            + _paragraph("판단 변경 조건", item.trigger)
            + _refs(review_id, item.basis_claim_ids)
            + "</section>"
        )
    if not insight.watch_items:
        parts.append('<p class="empty">확인 항목 없음</p>')
    return "".join(parts)


def _render_rating(review_id: str, scorable: bool) -> str:
    disabled = "" if scorable else " disabled"
    rows = []
    for criterion, label in _CRITERIA.items():
        options = '<option value="">미평가</option>' + "".join(
            f'<option value="{score}">{score}</option>' for score in range(4)
        )
        selects = "".join(
            f'<td><label class="sr-only" for="{review_id}-{side}-{criterion}">'
            f"{side} {label}</label>"
            f'<select id="{review_id}-{side}-{criterion}" data-side="{side}" '
            f'data-criterion="{criterion}"{disabled}>{options}</select></td>'
            for side in ("A", "B")
        )
        anchors = "<br>".join(_escape(anchor) for anchor in QUALITY_RUBRIC[criterion])
        rows.append(
            f'<tr><th scope="row">{label}<details><summary>점수 기준</summary>{anchors}'
            f"</details></th>{selects}</tr>"
        )
    return (
        f'<section class="rating" data-rating="{review_id}"><h3>직접 평가</h3>'
        '<p class="meta">최종 선호 하나만 선택하면 이 사례의 평가가 완료됩니다. '
        "세부 점수와 메모는 선택 사항입니다.</p>"
        f'<label class="winner">최종 선호 <select data-winner{disabled}>'
        '<option value="">미평가</option><option value="A">A 우세</option>'
        '<option value="B">B 우세</option><option value="tie">동률</option>'
        '<option value="both_fail">둘 다 불합격</option></select></label>'
        '<details class="detailed-ratings"><summary>세부 점수 입력 (선택)</summary>'
        '<p class="meta">0: 결함 · 1: 부족 · 2: 보완 필요 · 3: 근거에 맞고 구체적. '
        "미입력 점수는 평균에 포함되지 않습니다.</p>"
        '<table><thead><tr><th scope="col">평가 기준</th><th scope="col">A</th>'
        '<th scope="col">B</th></tr></thead>'
        f"<tbody>{''.join(rows)}</tbody></table></details>"
        f'<label class="notes">평가 메모 <textarea data-notes rows="3" maxlength="20000"{disabled} '
        'placeholder="문제 문장·근거 ID·판단 이유를 남겨 주세요."></textarea></label>'
        '<p class="save-status" role="status"></p></section>'
    )


def render_comparison(bundle: dict) -> tuple[str, dict]:
    """Return standalone blind HTML and a separate, private identity key.

    Input is the runner's schemaVersion=1 result.json. Only complete, validated
    same-evidence pairs can be rated. No attempt/cost/model metadata is embedded
    in the HTML or its public manifest.
    """
    if bundle.get("schemaVersion") != 1 or not isinstance(bundle.get("results"), list):
        raise ValueError("expected runner schemaVersion=1 with a results array")
    grouped: dict[str, list[dict]] = defaultdict(list)
    for entry in bundle["results"]:
        if (
            not isinstance(entry, dict)
            or not isinstance(entry.get("caseId"), str)
            or not entry["caseId"]
        ):
            raise ValueError("every result requires a non-empty caseId")
        if entry.get("variant") not in _VARIANTS:
            raise ValueError("unsupported comparison variant")
        grouped[entry["caseId"]].append(entry)
    expected_ids = bundle.get("caseIds", list(grouped))
    if not isinstance(expected_ids, list) or any(
        not isinstance(item, str) or not item for item in expected_ids
    ):
        raise ValueError("caseIds must be an array of non-empty strings")
    if len(expected_ids) != len(set(expected_ids)) or not set(grouped) <= set(expected_ids):
        raise ValueError("caseIds must uniquely include every result caseId")
    for case_id in expected_ids:
        grouped.setdefault(case_id, [])
    provenance = bundle.get("provenance") or {}
    seed = (
        provenance.get("policySha256") or provenance.get("inputSha256") or "report-insight-blind.v1"
    )
    private_key: dict = {
        "schemaVersion": 1,
        "seedDigest": _digest(seed),
        "provenance": provenance,
        "cases": [],
    }
    public_cases = []
    sections = []
    counts = {
        "totalCases": len(grouped),
        "eligiblePairs": 0,
        "failedPairs": 0,
        "incompletePairs": 0,
        "invalidPairs": 0,
    }
    for index, (case_id, entries) in enumerate(sorted(grouped.items())):
        review_id = "review-" + _digest(case_id)[:16]
        by_variant: dict[str, list[dict]] = defaultdict(list)
        for entry in entries:
            by_variant[entry["variant"]].append(entry)
        requests = []
        for entry in entries:
            try:
                requests.append(ReportInsightRequest.model_validate(entry.get("request")))
            except (ValueError, TypeError, ValidationError):
                continue
        request = requests[0] if requests else None
        conflicting_input = request is not None and any(
            _request_content(item) != _request_content(request) for item in requests
        )
        # One audience is one reviewer task; do not silently hide other outputs.
        conflicting_input = conflicting_input or (
            request is not None and len(request.audiences) != 1
        )
        order = list(_VARIANTS)
        if int(_digest([seed, case_id])[:8], 16) % 2:
            order.reverse()
        sides = {}
        outputs = {}
        identity = {"reviewId": review_id, "caseId": case_id, "sides": {}}
        for side, variant in zip(("A", "B"), order, strict=True):
            candidates = by_variant[variant]
            entry = candidates[0] if len(candidates) == 1 else None
            status, output = _validated_candidate(entry, request)
            if len(candidates) > 1 or conflicting_input:
                status, output = "invalid", None
            # A missing/invalid request on either entry is never a valid pair.
            if entry is not None and entry.get("status") == "success":
                try:
                    ReportInsightRequest.model_validate(entry.get("request"))
                except (ValueError, TypeError, ValidationError):
                    status, output = "invalid", None
            sides[side] = {"status": status}
            outputs[side] = output
            identity["sides"][side] = {
                "variant": variant,
                "status": status,
                "attemptIds": entry.get("attemptIds", []) if entry else [],
                "inputSha256": entry.get("inputSha256") if entry else None,
                "requestSha256": _digest(entry.get("request")) if entry else None,
                "outputSha256": (
                    _digest(output.model_dump(by_alias=True, mode="json")) if output else None
                ),
                "errorCode": entry.get("errorCode") if entry else None,
            }
        statuses = {item["status"] for item in sides.values()}
        pair_status = (
            "eligible"
            if statuses == {"success"}
            else "invalid"
            if "invalid" in statuses
            else "failed"
            if "failed" in statuses
            else "incomplete"
        )
        counts[
            {
                "eligible": "eligiblePairs",
                "failed": "failedPairs",
                "incomplete": "incompletePairs",
                "invalid": "invalidPairs",
            }[pair_status]
        ] += 1
        scorable = pair_status == "eligible"
        audience = request.audiences[0] if request else ""
        title = request.report.title if request else "원문 검증 실패 사례"
        public_cases.append(
            {
                "reviewId": review_id,
                "title": title,
                "audience": audience,
                "status": pair_status,
                "scorable": scorable,
                "sides": sides,
            }
        )
        private_key["cases"].append(identity)
        panels = []
        for side in ("A", "B"):
            output = outputs[side]
            rendered = (
                _render_insight(output.insights[0], request, review_id)
                if output is not None and request is not None
                else '<p class="empty">비교할 수 있는 유효 결과가 없습니다. '
                "이 사례는 미채점으로 남습니다.</p>"
            )
            panels.append(
                f'<section class="result panel"><h2>결과 {side} <span class="badge">'
                f"{_STATUS[sides[side]['status']]}</span></h2>"
                f'<div class="panel-body">{rendered}</div></section>'
            )
        dates = "미제공"
        if request is not None:
            anchor = request.report.report_end_date or request.report.report_date
            if anchor is None:
                anchor = max(
                    (item.published_at for item in request.findings if item.published_at),
                    default=None,
                )
            dates = str(anchor or "미제공")
        sections.append(
            f'<article class="case" data-case="{review_id}"{" hidden" if index else ""}>'
            f'<header class="case-header"><h2>{_escape(title)}</h2>'
            f"<p>{_AUDIENCES.get(audience, '관점 검증 실패')} · 판단 기준 날짜 {dates} · "
            f'<span class="badge">{_PAIR_STATUS[pair_status]}</span></p></header>'
            '<div class="comparison"><section class="sources panel"><h2>원문 근거</h2>'
            f'<div class="panel-body">{_render_sources(request, review_id)}</div></section>'
            f"{''.join(panels)}</div>" + _render_rating(review_id, scorable) + "</article>"
        )
    private_key["manifestHash"] = _digest(private_key)
    manifest = {
        "schemaVersion": 1,
        "manifestHash": private_key["manifestHash"],
        "criteria": list(_CRITERIA),
        "cases": public_cases,
        "counts": counts,
    }
    options = "".join(
        f'<option value="{item["reviewId"]}">{index + 1}. {_escape(item["title"])} · '
        f"{_AUDIENCES.get(item['audience'], '검증 실패')} · {_PAIR_STATUS[item['status']]}</option>"
        for index, item in enumerate(public_cases)
    )
    replacements = {
        "__CASE_OPTIONS__": options,
        "__CASE_SECTIONS__": "".join(sections),
        "__MANIFEST__": _script_json(manifest),
    }
    # Substitute once in the template; adversarial source strings that happen
    # to equal a placeholder must not be reinterpreted as template structure.
    page = re.sub(
        r"__(?:CASE_OPTIONS|CASE_SECTIONS|MANIFEST)__", lambda match: replacements[match[0]], _PAGE
    )
    return page, private_key


_PAGE = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; form-action 'none'; base-uri 'none'">
<title>리포트 인사이트 · 블라인드 평가</title>
<style>
:root{color-scheme:light;font:15px/1.6 system-ui,-apple-system,sans-serif;color:#193042;background:#f3f6fa}*{box-sizing:border-box}body{margin:0}main{max-width:1700px;margin:auto;padding:24px}h1,h2,h3,h4,h5,p{margin:0 0 12px}h1{font-size:24px}h2{font-size:18px}h3{font-size:16px}h4{font-size:16px;margin-top:24px}h5{font-size:15px}button,select,textarea{font:inherit;color:inherit}button,select{min-height:42px;border:1px solid #adbdcc;border-radius:8px;background:white;padding:8px 12px}button{cursor:pointer}button:disabled,select:disabled,textarea:disabled{opacity:.55;cursor:default}a{color:#135dad;overflow-wrap:anywhere}a:focus,button:focus,select:focus,textarea:focus{outline:3px solid #8dbae9;outline-offset:2px}.intro{max-width:1000px}.muted,.meta{font-size:13px;color:#506679}.stats{background:#e6edf5;padding:12px 16px;border-radius:10px;margin:16px 0}.toolbar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;position:sticky;top:0;background:#f3f6fa;padding:12px 0;z-index:2}.toolbar select{flex:1;min-width:160px;max-width:800px}.case-header{margin:12px 0}.badge{display:inline-block;background:#e7eef5;border-radius:6px;padding:2px 8px;font-size:12px;font-weight:500}.comparison{display:grid;grid-template-columns:minmax(240px,.85fr) repeat(2,minmax(260px,1fr));gap:16px}.panel,.rating{background:white;border:1px solid #d7e1eb;border-radius:12px;min-width:0}.panel>h2{padding:14px 18px;border-bottom:1px solid #d7e1eb;margin:0}.panel-body{padding:18px;max-height:calc(100vh - 235px);overflow:auto;scroll-margin-top:100px}.prose{white-space:pre-wrap;overflow-wrap:anywhere}.source-article{padding-bottom:20px;border-bottom:1px solid #d7e1eb;margin-bottom:20px}.source-claim{background:#f4f7fb;padding:12px;margin:14px 0;border-left:3px solid #779cbe;scroll-margin-top:16px}.source-claim:target{background:#fff1c9;border-left-color:#bb8500}.sentences{padding-left:24px}.sentences li{padding:8px 0}.sentences li p{margin:2px 0}.refs{font-size:12px;margin-top:10px}.output-item{padding:12px 0;border-bottom:1px solid #e3e9f0}.headline{font-size:19px}.empty{color:#657486;font-style:italic}.rating{margin-top:20px;padding:20px}.rating table{width:100%;border-collapse:collapse;table-layout:fixed}.rating th,.rating td{padding:10px 12px;border-bottom:1px solid #d7e1eb;text-align:left;vertical-align:top}.rating th:first-child{width:66%}.rating td select{width:100%}.rating details{font-weight:400;font-size:12px;color:#506679;margin-top:4px}.winner,.notes{display:block;margin-top:18px;font-weight:600}.winner select{margin-left:12px;min-width:180px}.notes textarea{display:block;width:100%;margin-top:8px;border:1px solid #adbdcc;border-radius:8px;padding:12px;resize:vertical}.save-status{font-size:13px;color:#315f43;margin-top:12px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}[hidden]{display:none!important}
@media(max-width:1100px){.comparison{grid-template-columns:repeat(2,minmax(0,1fr))}.sources{grid-column:1/-1}.sources .panel-body{max-height:380px}.result .panel-body{max-height:none}}
@media(max-width:700px){main{padding:14px}.comparison{grid-template-columns:minmax(0,1fr)}.sources{grid-column:auto}.panel-body{padding:14px;max-height:none!important}.toolbar{position:static}.toolbar select{flex-basis:100%;max-width:100%}.rating{padding:14px}.rating th,.rating td{padding:8px 4px}.rating th:first-child{width:58%}.rating td select{padding:5px;min-width:0}.winner select{margin:8px 0;display:block;width:100%}.badge{margin-top:4px}.stats{font-size:13px}}
</style></head><body><main>
<h1>리포트 인사이트 · 블라인드 평가</h1>
<p class="intro">원문과 A·B 결과를 읽고 사례마다 최종 선호 하나를 선택해 주세요. 세부 점수와 메모는 선택 사항입니다. 결과 위치는 사례마다 고정되고, 모델·처리 방식은 숨겨집니다. 오프라인 파일이며 평가 저장·다운로드 중 API를 호출하지 않습니다.</p>
<p class="meta">평가 기준: ① 사실·귀속 보존 ② 관점 업무 적합성 ③ 중요도·시급성 판단 ④ 종합·상충 처리 ⑤ 조건·경로·반증 ⑥ 구체적 확인 항목</p>
<p class="meta">생성 실패·미완료·검증 실패 사례는 미채점으로 남습니다. 성공한 사례의 선호와 전체 생성 성공률을 함께 확인해야 합니다. 사실 보존 0점은 별도의 중대한 품질 결함입니다.</p>
<div class="stats" id="counts" role="status"></div>
<nav class="toolbar" aria-label="사례 이동"><button id="previous" type="button">← 이전</button><select id="case-select" aria-label="평가 사례">__CASE_OPTIONS__</select><button id="next" type="button">다음 →</button><button id="download" type="button">평가 JSON 다운로드</button></nav>
<p id="storage-status" class="meta" role="status"></p>
__CASE_SECTIONS__
<script type="application/json" id="review-manifest">__MANIFEST__</script>
<script>
"use strict";
const manifest=JSON.parse(document.getElementById("review-manifest").textContent);
const storageKey="report-insight-blind:"+manifest.manifestHash;
const winners=new Set(["A","B","tie","both_fail"]);
let saved={};
try{const raw=JSON.parse(localStorage.getItem(storageKey)||"{}");if(raw&&typeof raw==="object"&&!Array.isArray(raw))saved=raw;}catch{document.getElementById("storage-status").textContent="이 브라우저에서 자동 저장을 읽을 수 없습니다. 다운로드로 평가를 보관해 주세요.";}
const drafts={};
function normalize(raw){const result={sides:{A:{scores:{}},B:{scores:{}}},winner:"",notes:""};for(const side of ["A","B"]){for(const criterion of manifest.criteria){const value=raw?.sides?.[side]?.scores?.[criterion];if(Number.isInteger(value)&&value>=0&&value<=3)result.sides[side].scores[criterion]=value;}}if(winners.has(raw?.winner))result.winner=raw.winner;if(typeof raw?.notes==="string")result.notes=raw.notes.slice(0,20000);return result;}
function complete(rating){return winners.has(rating.winner);}
function updateCounts(){const done=manifest.cases.filter(item=>item.scorable&&complete(drafts[item.reviewId])).length;const c=manifest.counts;document.getElementById("counts").textContent=`전체 ${c.totalCases}개 · 평가 가능 ${c.eligiblePairs}개 · 생성 실패 ${c.failedPairs}개 · 미완료 ${c.incompletePairs}개 · 검증 실패 ${c.invalidPairs}개 · 평가 완료 ${done}/${c.eligiblePairs}개`;}
function persist(){try{localStorage.setItem(storageKey,JSON.stringify(drafts));document.getElementById("storage-status").textContent="현재 브라우저에 자동 저장됩니다. 다른 브라우저·파일 위치에서 이어서 보려면 같은 저장소가 필요합니다. 완료 후 JSON을 다운로드해 주세요.";return true;}catch{document.getElementById("storage-status").textContent="자동 저장이 차단됐습니다. 페이지를 닫기 전에 JSON을 다운로드해 주세요.";return false;}}
for(const item of manifest.cases){const section=document.querySelector(`[data-rating="${item.reviewId}"]`);const rating=normalize(item.scorable?saved[item.reviewId]:null);drafts[item.reviewId]=rating;for(const select of section.querySelectorAll("[data-criterion]")){const value=rating.sides[select.dataset.side].scores[select.dataset.criterion];select.value=value===undefined?"":String(value);}section.querySelector("[data-winner]").value=rating.winner;section.querySelector("[data-notes]").value=rating.notes;function update(){if(!item.scorable)return;for(const select of section.querySelectorAll("[data-criterion]")){const scores=rating.sides[select.dataset.side].scores;if(select.value==="")delete scores[select.dataset.criterion];else scores[select.dataset.criterion]=Number(select.value);}rating.winner=section.querySelector("[data-winner]").value;rating.notes=section.querySelector("[data-notes]").value;const stored=persist();section.querySelector(".save-status").textContent=(complete(rating)?"평가 완료":"평가 진행 중")+(stored?" · 자동 저장됨":" · 다운로드 필요");updateCounts();}section.addEventListener("change",update);section.querySelector("[data-notes]").addEventListener("input",update);section.querySelector(".save-status").textContent=item.scorable?(complete(rating)?"저장된 평가 완료":"미평가 / 평가 진행 중"):"생성·검증 상태로 인해 평가할 수 없습니다.";}
const selector=document.getElementById("case-select");
function show(id){for(const section of document.querySelectorAll("[data-case]"))section.hidden=section.dataset.case!==id;selector.value=id;document.getElementById("previous").disabled=selector.selectedIndex<=0;document.getElementById("next").disabled=selector.selectedIndex<0||selector.selectedIndex>=manifest.cases.length-1;}
selector.addEventListener("change",()=>show(selector.value));
document.getElementById("previous").addEventListener("click",()=>{if(selector.selectedIndex>0)show(selector.options[selector.selectedIndex-1].value);});
document.getElementById("next").addEventListener("click",()=>{if(selector.selectedIndex<manifest.cases.length-1)show(selector.options[selector.selectedIndex+1].value);});
function scoredSide(side){const values=Object.values(side.scores);return {scores:side.scores,ratedCriteriaCount:values.length,meanScore:values.length?values.reduce((sum,value)=>sum+value,0)/values.length:null};}
document.getElementById("download").addEventListener("click",()=>{const ratings=manifest.cases.filter(item=>item.scorable).map(item=>{const draft=drafts[item.reviewId];return {reviewId:item.reviewId,sides:{A:scoredSide(draft.sides.A),B:scoredSide(draft.sides.B)},winner:draft.winner||null,notes:draft.notes,complete:complete(draft)};});const judgedCount=ratings.filter(item=>item.complete).length;const excluded=manifest.cases.filter(item=>!item.scorable).map(item=>({reviewId:item.reviewId,status:item.status,sides:item.sides}));const result={schemaVersion:1,manifestHash:manifest.manifestHash,reviewedAt:new Date().toISOString(),counts:{...manifest.counts,judgedCount,unjudgedEligiblePairs:manifest.counts.eligiblePairs-judgedCount},coverage:{judgedReviewIds:ratings.filter(item=>item.complete).map(item=>item.reviewId),unjudgedReviewIds:ratings.filter(item=>!item.complete).map(item=>item.reviewId),excludedReviewIds:excluded.map(item=>item.reviewId),missingResultReviewIds:manifest.cases.filter(item=>Object.values(item.sides).some(side=>side.status==="missing")).map(item=>item.reviewId)},ratings,excluded,qualityImprovementClaimed:false};const blob=new Blob([JSON.stringify(result,null,2)],{type:"application/json"});const url=URL.createObjectURL(blob);const link=document.createElement("a");link.href=url;link.download="report-insight-blind-ratings-"+manifest.manifestHash.slice(0,12)+".json";document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);});
updateCounts();show(manifest.cases[0]?.reviewId||"");
</script></main></body></html>"""  # noqa: E501


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True, help="Saved runner result.json")
    parser.add_argument("--html", type=Path, required=True, help="Offline blind review output")
    parser.add_argument(
        "--private-key", type=Path, required=True, help="Separate private identity key"
    )
    args = parser.parse_args(argv)
    if len({args.bundle.resolve(), args.html.resolve(), args.private_key.resolve()}) != 3:
        parser.error("bundle, html, and private-key paths must be different")
    page, private_key = render_comparison(json.loads(args.bundle.read_text(encoding="utf-8")))
    for path, content in (
        (args.html, page),
        (args.private_key, json.dumps(private_key, ensure_ascii=False, indent=2)),
    ):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        path.chmod(0o600)
    print(
        json.dumps(
            {
                "html": str(args.html),
                "privateKey": str(args.private_key),
                "caseCount": len(private_key["cases"]),
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
