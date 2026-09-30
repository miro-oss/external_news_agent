"""Revalidate frozen provider answers locally; never generate or rewrite history."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from pydantic import ValidationError

from app.core.parser import JsonObjectParseError
from app.eval.report_insight_run import (
    EvaluationStopped,
    atomic_save,
    file_digest,
    require,
    runtime_hashes,
    stamp,
    verify_recorded,
)
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_guard import has_blanket_insufficient_headline
from app.llm.report_insight_service import (
    PROMPT_VERSION,
    RUBRIC_VERSION,
    _eligible_report_request,
    _empty_synthesis,
    _validated_map_output,
    _validated_output,
    _validated_reduce_output,
)
from app.schemas.report_insight import ReportInsightMapOutput, ReportInsightRequest


def provider_response(attempt: dict) -> ProviderResponse:
    raw = attempt.get("providerRawResponse") or {}
    require(
        attempt["status"] == "success" and isinstance(attempt.get("providerText"), str),
        "NO_COMPLETED_PROVIDER_RESPONSE",
    )
    return ProviderResponse(
        text=attempt["providerText"],
        provider="openai",
        model=attempt["resolvedModel"],
        usage=ProviderUsage(),
        truncated=raw.get("status") == "incomplete",
    )


def saved_reduce_context(
    attempt: dict, request: ReportInsightRequest, mapped, *, eligible_request=None
):
    # Use the first REDUCE wire, before repair escapes its delimiters. Do not
    # rerun retrieval and silently grant evidence the original model never saw.
    text = attempt["wireRequest"]["input"]
    opening, closing = "<report-insight-input>", "</report-insight-input>"
    require(text.count(opening) == text.count(closing) == 1, "INVALID_REDUCE_INPUT")
    payload = json.loads(text.split(opening, 1)[1].split(closing, 1)[0])
    require(
        payload["report"] == request.report.model_dump(mode="json", by_alias=True)
        and payload["audiences"] == request.audiences
        and ReportInsightMapOutput.model_validate(payload["assessedPriorities"]) == mapped,
        "REDUCE_INPUT_CHANGED",
    )
    source = {
        claim.id: (finding, claim) for finding in request.findings for claim in finding.claims
    }
    allowed = {}
    for group in payload["retrievedEvidence"]:
        audience = group["audience"]
        require(audience in request.audiences and audience not in allowed, "INVALID_REDUCE_INPUT")
        refs = []
        for item in group["evidence"]:
            ref = item["claimId"]
            require(ref in source and ref not in refs, "INVALID_RETRIEVED_REFERENCE")
            finding, claim = source[ref]
            sentences = {sentence.index: sentence.text for sentence in finding.sentences}
            require(
                item["findingId"] == finding.id
                and item["articleId"] == finding.article_id
                and item["attributedTo"] == claim.attributed_to
                and item["publishedAt"]
                == (finding.published_at.isoformat() if finding.published_at else None)
                and item["text"] == claim.text
                and item["claimType"] == claim.claim_type
                and item["evidenceSentenceIds"] == claim.evidence_sentence_ids
                and item["sentences"]
                == [
                    {"index": index, "text": sentences[index]}
                    for index in sorted(claim.evidence_sentence_ids)
                ],
                "RETRIEVED_SOURCE_CHANGED",
            )
            refs.append(ref)
        allowed[audience] = refs
    require(set(allowed) == set(request.audiences), "INVALID_REDUCE_INPUT")
    # Authenticate the saved evidence against the original snapshot first, then
    # narrow its scope to claims today's grounding filter still accepts. Never
    # recreate retrieval or expose another claim the recorded model did not see.
    eligible = (
        eligible_request if eligible_request is not None else _eligible_report_request(request)
    )
    eligible_ids = {claim.id for finding in eligible.findings for claim in finding.claims}
    return {
        audience: [ref for ref in refs if ref in eligible_ids] for audience, refs in allowed.items()
    }


def rejection_code(error: Exception) -> str:
    """Only fixed diagnostics are exported, never Pydantic/provider input text."""
    if isinstance(error, EvaluationStopped):
        return str(error)
    if isinstance(error, (ValidationError, JsonObjectParseError)):
        return "INVALID_SCHEMA_OR_JSON"
    message = str(error)
    for marker, code in (
        ("잘렸", "TRUNCATED_OUTPUT"),
        ("overview", "RELATED_SYNTHESIS_REQUIRED"),
        ("headline", "INSUFFICIENT_HEADLINE_WITH_RELATED_EVIDENCE"),
        ("과거", "PAST_DATE_URGENCY"),
        ("이미 지난", "PAST_DATE_URGENCY"),
        ("시급", "PAST_DATE_URGENCY"),
        ("사실값", "GROUNDING_MISMATCH"),
        ("basisClaimIds", "REFERENCE_SCOPE"),
    ):
        if marker in message:
            return code
    return "CONTRACT_REJECTED"


def replay_job(result: dict, attempts: list[dict]) -> dict:
    original_request = ReportInsightRequest.model_validate(result["request"])
    request = _eligible_report_request(original_request)
    diagnostic = {
        "caseId": result["caseId"],
        "variant": result["variant"],
        "originalStatus": result["status"],
        "originalErrorCode": result.get("errorCode"),
        "status": "not_attempted",
        "usedAttemptIds": [],
        "errorCode": None,
        "contentContractPassed": False,
        "contentErrorCode": None,
        "synthesisFlags": [],
        "insights": None,
    }
    stages = {}
    for attempt in attempts:
        stages.setdefault(attempt["stage"], []).append(attempt)
    if not stages:
        return diagnostic
    try:
        # The final repair is authoritative. Never select an earlier answer
        # merely because a newer validator likes it better.
        if result["variant"] == "single_call":
            last = stages["SINGLE"][-1]
            diagnostic["usedAttemptIds"].append(last["attemptId"])
            output = _validated_output(provider_response(last), request, require_synthesis=False)
        else:
            last_map = stages["MAP"][-1]
            diagnostic["usedAttemptIds"].append(last_map["attemptId"])
            mapped = _validated_map_output(provider_response(last_map), request)
            if "REDUCE" in stages:
                first, last = stages["REDUCE"][0], stages["REDUCE"][-1]
                diagnostic["usedAttemptIds"].append(last["attemptId"])
                allowed = saved_reduce_context(
                    first, original_request, mapped, eligible_request=request
                )
                output = _validated_reduce_output(
                    provider_response(last), request, mapped, allowed, require_synthesis=False
                )
            elif any(
                assessment.axes.directness is not None and assessment.axes.directness > 0
                for insight in mapped.insights
                for assessment in insight.assessments
            ):
                diagnostic.update(status="incomplete", errorCode="MISSING_REDUCE_RESPONSE")
                return diagnostic
            else:
                empty = _empty_synthesis(mapped)
                output = _validated_output(
                    ProviderResponse(
                        empty.model_dump_json(by_alias=True), "openai", "saved", ProviderUsage()
                    ),
                    request,
                )
        diagnostic["contentContractPassed"] = True
        for insight in output.insights:
            related = any(
                item.axes.directness is not None and item.axes.directness > 0
                for item in insight.assessments
            )
            if related and not insight.overview:
                diagnostic["synthesisFlags"].append("related_overview_missing")
            if related and not (insight.overview or insight.implications or insight.watch_items):
                diagnostic["synthesisFlags"].append("related_synthesis_empty")
            if related and has_blanket_insufficient_headline(insight.headline):
                diagnostic["synthesisFlags"].append("insufficient_headline_with_related_evidence")
        _validated_output(
            ProviderResponse(
                output.model_dump_json(by_alias=True), "openai", "saved", ProviderUsage()
            ),
            request,
        )
        diagnostic.update(
            status="accepted", insights=output.model_dump(mode="json", by_alias=True)["insights"]
        )
    except (EvaluationStopped, ValueError, KeyError) as error:
        diagnostic.update(status="rejected", errorCode=rejection_code(error))
        if not diagnostic["contentContractPassed"]:
            diagnostic["contentErrorCode"] = diagnostic["errorCode"]
    return diagnostic


def replay(source_dir: Path, output_dir: Path) -> dict:
    source_dir, output_dir = source_dir.resolve(), output_dir.resolve()
    require(
        output_dir != source_dir and not output_dir.is_relative_to(source_dir),
        "ORIGINAL_OUTPUT_PROTECTED",
    )
    manifest = json.loads((source_dir / "manifest.json").read_text(encoding="utf-8"))
    state = json.loads((source_dir / "result.json").read_text(encoding="utf-8"))
    verify_recorded(source_dir, manifest, state)
    attempts = {item["attemptId"]: item for item in state["attempts"]}
    cases = [
        replay_job(result, [attempts[index] for index in result["attemptIds"]])
        for result in state["results"]
    ]
    variants = {}
    for variant in ("single_call", "staged"):
        selected = [item for item in cases if item["variant"] == variant]
        variants[variant] = {
            "originalAccepted": sum(item["originalStatus"] == "success" for item in selected),
            "contentContractPassed": sum(item["contentContractPassed"] for item in selected),
            "revalidatedAccepted": sum(item["status"] == "accepted" for item in selected),
            "newlyAccepted": sum(
                item["originalStatus"] == "failed" and item["status"] == "accepted"
                for item in selected
            ),
            "newlyRejected": sum(
                item["originalStatus"] == "success" and item["status"] == "rejected"
                for item in selected
            ),
            "incomplete": sum(item["status"] == "incomplete" for item in selected),
            "notAttempted": sum(item["status"] == "not_attempted" for item in selected),
        }
    report = {
        "schemaVersion": 1,
        "kind": "stored-response-revalidation-not-new-generation",
        "createdAt": stamp(),
        "extraPaidCalls": 0,
        "qualityImprovementClaimed": False,
        "sourceManifestSha256": file_digest(source_dir / "manifest.json"),
        "sourceCheckpointSha256": state["checkpointSha256"],
        "originalProvenance": manifest["provenance"],
        "validationRuntime": {
            "promptVersion": PROMPT_VERSION,
            "rubricVersion": RUBRIC_VERSION,
            "sourceSha256s": runtime_hashes(),
            "replaySourceSha256": file_digest(Path(__file__)),
        },
        "variants": variants,
        "cases": cases,
        "limitations": [
            "Saved answers only; current prompting effectiveness is not measured.",
            "Missing REDUCE answers are incomplete, not recovered full results.",
            "Original blind judgments remain attached exclusively to the original experiment.",
        ],
    }
    output_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    atomic_save(output_dir / "revalidation.json", report)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = replay(args.source_dir, args.output_dir)
        print(
            json.dumps(
                {
                    key: report[key]
                    for key in ("kind", "extraPaidCalls", "qualityImprovementClaimed", "variants")
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    except Exception as error:
        code = str(error) if isinstance(error, EvaluationStopped) else "REPLAY_INPUT_ERROR"
        print(json.dumps({"status": "stopped", "code": code}))
        raise SystemExit(2) from None


if __name__ == "__main__":
    main()
