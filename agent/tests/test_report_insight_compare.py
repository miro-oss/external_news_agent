"""Saved-output blind presentation and offline rating tests; no provider calls."""

import json
import shutil
import subprocess
from copy import deepcopy
from html.parser import HTMLParser

import pytest

from app.eval.report_insight_compare import main, render_comparison


def request(case="alpha", text="장비 발주 계약을 체결했다."):
    return {
        "idempotencyKey": "review:" + case,
        "plan": "FREE",
        "audiences": ["EQUIPMENT_MAKER"],
        "report": {
            "id": 1,
            "title": "검증 리포트 " + case,
            "reportScope": "DAILY",
            "reportDate": "2026-09-30",
        },
        "findings": [
            {
                "id": 10,
                "articleId": 100,
                "articleTitle": "원문 기사",
                "canonicalUrl": "https://example.invalid/news/100",
                "publishedAt": "2026-09-29",
                "topicName": "장비",
                "claims": [
                    {
                        "id": "10:0",
                        "text": text,
                        "claimType": "FACT",
                        "attributedTo": None,
                        "evidenceSentenceIds": [0],
                    }
                ],
                "sentences": [{"index": 0, "text": text}],
            }
        ],
    }


def output(label="후보 요약"):
    return {
        "insights": [
            {
                "audience": "EQUIPMENT_MAKER",
                "headline": label,
                "overview": [
                    {
                        "text": "장비 발주 계약을 체결했다.",
                        "basisClaimIds": ["10:0"],
                        "assumption": "계약 범위가 유지되는 경우",
                    }
                ],
                "assessments": [
                    {
                        "findingId": 10,
                        "reason": "장비 발주 판단에 직접 관련된다.",
                        "basisClaimIds": ["10:0"],
                        "axes": {"directness": 3, "impact": 2, "urgency": None, "novelty": None},
                    }
                ],
                "implications": [
                    {
                        "text": "설치 일정을 확인할 필요가 있다.",
                        "mechanism": "발주가 설치 준비의 선행 조건이 된다.",
                        "basisClaimIds": ["10:0"],
                        "assumption": "계약이 유지되는 경우",
                        "falsifiedBy": "발주 취소 발표",
                    }
                ],
                "watchItems": [
                    {
                        "topic": "설치",
                        "indicator": "설치 일정 발표",
                        "trigger": "발주 취소가 확인되면 판단을 보류한다.",
                        "basisClaimIds": ["10:0"],
                    }
                ],
            }
        ],
        "meta": {"model": "DO_NOT_SHOW_META_MODEL", "attemptIds": ["DO_NOT_SHOW_META_ATTEMPT"]},
    }


def bundle(cases=("alpha",), seed="seed-1"):
    results = []
    for case in cases:
        for variant, label in (("single_call", "첫 번째 문안"), ("staged", "두 번째 문안")):
            results.append(
                {
                    "caseId": case,
                    "variant": variant,
                    "status": "success",
                    "request": request(case),
                    "response": output(label),
                    "inputSha256": "INPUT_HASH_" + case,
                    "attemptIds": ["ATTEMPT_" + case + "_" + variant],
                    "latencyMs": 20,
                }
            )
    return {
        "schemaVersion": 1,
        "caseIds": list(cases),
        "provenance": {
            "model": "DO_NOT_SHOW_PROVIDER_MODEL",
            "inputSha256": "DATASET_HASH",
            "policySha256": seed,
        },
        "results": results,
        "totals": {"observedCostEstimatedUsd": 10},
    }


class PageParser(HTMLParser):
    def __init__(self, page):
        super().__init__()
        self.tags = []
        self.scripts = []
        self.script = None
        self.feed(page)

    def handle_starttag(self, tag, attrs):
        self.tags.append((tag, dict(attrs)))
        if tag == "script":
            self.script = []

    def handle_endtag(self, tag):
        if tag == "script" and self.script is not None:
            self.scripts.append("".join(self.script))
            self.script = None

    def handle_data(self, data):
        if self.script is not None:
            self.script.append(data)


def manifest(page):
    return json.loads(PageParser(page).scripts[0])


def test_original_dates_types_sources_and_both_normalized_presentations_are_visible():
    page, key = render_comparison(bundle())
    assert manifest(page)["counts"] == {
        "totalCases": 1,
        "eligiblePairs": 1,
        "failedPairs": 0,
        "incompletePairs": 0,
        "invalidPairs": 0,
    }
    assert "2026-09-30" in page and "2026-09-29" in page
    assert "FACT" in page and "10:0" in page and "문장 0" in page
    assert page.count("<strong>영향 경로</strong>") == 2
    assert page.count("설치 일정 발표") == 2
    assert page.count("2.50/3") == 2
    assert key["manifestHash"] == manifest(page)["manifestHash"]


def test_variant_model_attempt_and_cost_metadata_are_only_in_private_key():
    page, key = render_comparison(bundle())
    for secret in (
        "single_call",
        "staged",
        "DO_NOT_SHOW",
        "ATTEMPT_alpha",
        "observedCostEstimatedUsd",
        "latencyMs",
        "INPUT_HASH_alpha",
    ):
        assert secret not in page
    assert {side["variant"] for side in key["cases"][0]["sides"].values()} == {
        "single_call",
        "staged",
    }
    assert key["cases"][0]["sides"]["A"]["attemptIds"]
    assert "caseId" not in manifest(page)["cases"][0]


def test_untrusted_content_is_text_and_cannot_break_out_of_json_or_html():
    attack = '</script><img src=x onerror="alert(1)"><script>bad()</script>\u2028&\u2029END'
    data = bundle()
    for entry in data["results"]:
        entry["request"] = request(text=attack)
        entry["request"]["report"]["title"] = attack
        entry["request"]["findings"][0]["canonicalUrl"] = "javascript:alert(1)"
        insight = entry["response"]["insights"][0]
        insight["headline"] = attack
        insight["assessments"][0]["reason"] = attack
        insight["implications"][0]["mechanism"] = attack
        insight["watchItems"][0]["indicator"] = attack
    page, _ = render_comparison(data)
    parsed = PageParser(page)
    assert len(parsed.scripts) == 2
    assert not any(tag == "img" for tag, _ in parsed.tags)
    assert not any("onerror" in attrs for _, attrs in parsed.tags)
    assert not any(attrs.get("href", "").startswith("javascript:") for _, attrs in parsed.tags)
    assert "&lt;/script&gt;&lt;img" in page
    assert "\\u003c/script\\u003e" in parsed.scripts[0]
    assert "\\u2028" in parsed.scripts[0] and "\\u2029" in parsed.scripts[0]
    assert manifest(page)["cases"][0]["title"] == attack


def test_seeded_sides_are_deterministic_independent_of_entry_order_and_can_flip():
    data = bundle(("beta", "alpha"))
    page, key = render_comparison(data)
    shuffled = deepcopy(data)
    shuffled["results"].reverse()
    assert render_comparison(shuffled) == (page, key)
    placements = {
        render_comparison(bundle(seed=str(index)))[1]["cases"][0]["sides"]["A"]["variant"]
        for index in range(20)
    }
    assert placements == {"single_call", "staged"}


def test_manifest_hash_binds_candidate_text_and_source_when_attempt_hashes_do_not_change():
    data = bundle()
    _, original = render_comparison(data)
    changed = deepcopy(data)
    changed["results"][0]["response"]["insights"][0]["headline"] = "수정된 후보 문안"
    _, candidate_key = render_comparison(changed)
    assert candidate_key["manifestHash"] != original["manifestHash"]
    variant_side = next(
        side
        for side, value in original["cases"][0]["sides"].items()
        if value["variant"] == "single_call"
    )
    assert (
        candidate_key["cases"][0]["sides"][variant_side]["outputSha256"]
        != original["cases"][0]["sides"][variant_side]["outputSha256"]
    )
    changed = deepcopy(data)
    for entry in changed["results"]:
        entry["inputSha256"] = None
        entry["request"]["findings"][0]["sentences"][0]["text"] = "검증 원문이 수정됐다."
    _, source_key = render_comparison(changed)
    assert source_key["manifestHash"] != original["manifestHash"]
    assert source_key["cases"][0]["sides"]["A"]["requestSha256"]


def test_template_placeholders_inside_original_text_remain_literal_data():
    data = bundle()
    marker = "__CASE_OPTIONS__ __CASE_SECTIONS__ __MANIFEST__"
    for entry in data["results"]:
        entry["request"]["report"]["title"] = marker
        entry["request"]["findings"][0]["claims"][0]["text"] = marker
        entry["response"]["insights"][0]["headline"] = marker
    page, _ = render_comparison(data)
    assert manifest(page)["cases"][0]["title"] == marker
    assert len(PageParser(page).scripts) == 2
    assert page.count(marker) >= 5


@pytest.mark.parametrize(
    "kind",
    [
        "failed",
        "pending",
        "missing",
        "malformed",
        "wrong_audience",
        "missing_assessment",
        "unknown_source",
        "duplicate",
        "changed_source",
    ],
)
def test_failed_incomplete_and_invalid_pairs_cannot_be_rated(kind):
    data = bundle()
    entry = data["results"][1]
    expected = "invalid"
    if kind in ("failed", "pending"):
        entry["status"], entry["response"] = kind, None
        expected = "failed" if kind == "failed" else "incomplete"
    elif kind == "missing":
        data["results"].pop()
        expected = "incomplete"
    elif kind == "malformed":
        entry["response"] = {"insights": []}
    elif kind == "wrong_audience":
        entry["response"]["insights"][0]["audience"] = "IT_INFRA"
    elif kind == "missing_assessment":
        entry["response"]["insights"][0]["assessments"] = []
    elif kind == "unknown_source":
        entry["response"]["insights"][0]["overview"][0]["basisClaimIds"] = ["999:0"]
    elif kind == "duplicate":
        data["results"].append(deepcopy(entry))
    elif kind == "changed_source":
        entry["request"]["findings"][0]["claims"][0]["text"] = "계약을 체결하지 않았다."
    page, _ = render_comparison(data)
    public = manifest(page)
    assert public["cases"][0]["status"] == expected
    assert not public["cases"][0]["scorable"]
    assert public["counts"]["eligiblePairs"] == 0
    assert all(
        "disabled" in attrs
        for tag, attrs in PageParser(page).tags
        if tag in ("select", "textarea")
        and ("data-winner" in attrs or "data-criterion" in attrs or "data-notes" in attrs)
    )


def test_expected_case_with_both_results_missing_is_in_population():
    data = bundle()
    data["caseIds"].append("not-generated")
    page, key = render_comparison(data)
    public = manifest(page)
    assert public["counts"]["totalCases"] == 2
    assert public["counts"]["incompletePairs"] == 1
    assert len(key["cases"]) == 2


def test_cli_writes_separate_private_artifacts_and_rejects_path_collision(tmp_path):
    source, page, key = (
        tmp_path / name for name in ("bundle.json", "review.html", "private-key.json")
    )
    source.write_text(json.dumps(bundle()), encoding="utf-8")
    main(["--bundle", str(source), "--html", str(page), "--private-key", str(key)])
    assert page.exists() and key.exists()
    assert page.stat().st_mode & 0o777 == 0o600
    assert key.stat().st_mode & 0o777 == 0o600
    assert json.loads(key.read_text())["manifestHash"] == manifest(page.read_text())["manifestHash"]
    with pytest.raises(SystemExit):
        main(["--bundle", str(source), "--html", str(source), "--private-key", str(key)])


_BROWSER_STUB = r"""
const vm=require('node:vm');
const input=JSON.parse(process.argv[1]);
const m=input.manifest;
class Element{constructor(dataset={}){this.dataset=dataset;this.listeners={};this.value='';this.textContent='';this.disabled=false;this.hidden=false;}addEventListener(name,fn){this.listeners[name]=fn;}remove(){}click(){this.listeners.click?.();}}
const elements={};for(const id of ['review-manifest','counts','storage-status','case-select','previous','next','download'])elements[id]=new Element();
elements['review-manifest'].textContent=JSON.stringify(m);
const selector=elements['case-select'];selector.options=m.cases.map(item=>({value:item.reviewId}));
Object.defineProperty(selector,'value',{get(){return this.options[this.selectedIndex]?.value||'';},set(value){this.selectedIndex=this.options.findIndex(item=>item.value===value);}});
const sections={};const cases=m.cases.map(item=>new Element({case:item.reviewId}));
for(const item of m.cases){const section=new Element();section.scores=['A','B'].flatMap(side=>m.criteria.map(criterion=>new Element({side,criterion})));section.winner=new Element();section.notes=new Element();section.status=new Element();section.querySelectorAll=()=>section.scores;section.querySelector=selector=>selector==='[data-winner]'?section.winner:selector==='[data-notes]'?section.notes:section.status;sections[item.reviewId]=section;}
let downloaded;const stored=new Map();
const context={document:{getElementById:id=>elements[id],querySelector:selector=>sections[selector.match(/data-rating="([^"]+)"/)[1]],querySelectorAll:()=>cases,createElement:()=>new Element(),body:{append(){}}},localStorage:{getItem:key=>stored.get(key),setItem:(key,value)=>stored.set(key,value)},Blob:class{constructor(parts){downloaded=JSON.parse(parts[0]);}},URL:{createObjectURL:()=> 'blob:offline',revokeObjectURL(){}},setTimeout:()=>0};
vm.runInNewContext(input.script,context);
const first=sections[m.cases[0].reviewId];first.winner.value='A';first.listeners.change();
first.scores.find(item=>item.dataset.side==='A'&&item.dataset.criterion===m.criteria[0]).value='2';first.listeners.change();
first.notes.value='<img onerror=evil()>';first.notes.listeners.input();
elements.download.listeners.click();
console.log(JSON.stringify({downloaded,stored:[...stored],counts:elements.counts.textContent,notes:first.notes.value}));
"""  # noqa: E501


@pytest.mark.skipif(
    shutil.which("node") is None,
    reason="Node is only needed for offline browser-script verification",
)
def test_offline_rating_script_persists_correct_keys_and_one_choice_completes_without_scores():
    data = bundle(("alpha", "beta", "gamma"))
    data["results"][-1]["status"] = "failed"
    data["results"][-1]["response"] = None
    page, key = render_comparison(data)
    parsed = PageParser(page)
    public = manifest(page)
    result = subprocess.run(
        [
            shutil.which("node"),
            "-e",
            _BROWSER_STUB,
            json.dumps({"manifest": public, "script": parsed.scripts[1]}),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    actual = json.loads(result.stdout)
    exported = actual["downloaded"]
    assert exported["manifestHash"] == key["manifestHash"]
    assert exported["counts"]["judgedCount"] == 1
    assert exported["counts"]["eligiblePairs"] == 2
    assert exported["counts"]["failedPairs"] == 1
    assert len(exported["coverage"]["unjudgedReviewIds"]) == 1
    assert len(exported["excluded"]) == 1
    first = exported["ratings"][0]
    assert first["reviewId"] == key["cases"][0]["reviewId"]
    assert first["winner"] == "A" and first["complete"]
    assert first["sides"]["A"]["scores"] == {"factual_integrity": 2}
    assert first["sides"]["A"]["meanScore"] == 2
    assert first["sides"]["B"]["meanScore"] is None
    assert first["sides"]["B"]["ratedCriteriaCount"] == 0
    assert actual["stored"][0][0] == "report-insight-blind:" + key["manifestHash"]
    stored = json.loads(actual["stored"][0][1])
    assert stored[first["reviewId"]]["winner"] == "A"
    assert first["notes"] == "<img onerror=evil()>"
    assert not exported["qualityImprovementClaimed"]
