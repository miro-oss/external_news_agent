"""Offline review of recorded assessment prose against its original request.

Replays public assessment prose guards only, not native decision validity or the
whole pipeline. Unparseable responses remain unmeasured. No fabricated labels.
"""

import argparse
import json
from collections import Counter
from pathlib import Path

from app.eval.report_insight_paired_compare import _jobs
from app.eval.report_insight_run import atomic_save, digest, require
from app.eval.report_insight_semantic_judge import validate_packet
from app.eval.report_insight_semantic_review import _annotations
from app.llm.report_insight_assessment import _parse_draft, project_public_assessment
from app.llm.report_insight_guard import report_reference_date
from app.llm.report_insight_service import (
    _assessment_prose_errors,
    _eligible_report_request,
    _source_context,
)
from app.schemas.report_insight import ReportInsightRequest


def export_guard_packet(manifest: dict, state: dict) -> dict:
    from app.eval.report_insight_operational import runtime_hashes

    _jobs(manifest, state)
    jobs = {job["jobId"]: job for job in state["results"]}
    units, unreviewable, requests = [], [], {}
    for attempt in state.get("attempts", []):
        if not attempt["stage"].startswith(("MAP-", "REVIEW-", "EXPLAIN-")):
            continue
        job = jobs[attempt["jobId"]]
        original = ReportInsightRequest.model_validate(
            manifest["cases"][job["caseIndex"]]["request"]
        ).model_copy(update={"audiences": [job["audience"]]})
        projection = _eligible_report_request(original)
        projection_json = projection.model_dump(mode="json", by_alias=True)
        request_hash = digest(projection_json)
        requests[request_hash] = {
            "originalRequest": original.model_dump(mode="json", by_alias=True),
            "guardRequest": projection_json,
        }
        try:
            parsed = _parse_draft(attempt.get("providerText", ""), projection)
        except (ValueError, KeyError, TypeError):
            unreviewable.append(
                {"attemptId": attempt["attemptId"], "reason": "native_prose_unparseable"}
            )
            continue
        claims, evidence = _source_context(projection)
        findings = {finding.id: finding for finding in projection.findings}
        for audience, records in parsed.assessments.items():
            require(audience == job["audience"], "GUARD_AUDIENCE_MISMATCH")
            for native in records.values():
                require(native.finding_id in findings, "GUARD_UNKNOWN_FINDING")
                finding = findings[native.finding_id]
                assessment = project_public_assessment(native)
                refs = assessment.basis_claim_ids or [claim.id for claim in finding.claims]
                require(set(refs) <= {claim.id for claim in finding.claims}, "GUARD_UNKNOWN_REF")
                errors = _assessment_prose_errors(
                    assessment, native, refs, evidence, claims, projection, audience=audience
                )
                sources = []
                for ref in refs:
                    claim = claims[ref]
                    sentences = {row.index: row.text for row in finding.sentences}
                    sources.append(
                        {
                            "claimId": ref,
                            "claimText": claim.text,
                            "claimType": claim.claim_type,
                            "attributedTo": claim.attributed_to,
                            "sentences": [
                                {"sentenceId": index, "text": sentences[index]}
                                for index in claim.evidence_sentence_ids
                            ],
                        }
                    )
                for field, statement in (
                    ("reason", native.reason),
                    ("decision.connection.condition", native.condition),
                ):
                    if statement is None:
                        continue
                    local_errors = [
                        error
                        for coordinate, error in errors
                        if coordinate == f"reason nativeFields={field}" or coordinate == field
                    ]
                    reference = report_reference_date(projection)
                    identifier = (
                        f"{job['jobId']}:{attempt['attemptId']}:{native.finding_id}:{field}"
                    )
                    units.append(
                        {
                            "unitId": identifier,
                            "jobId": job["jobId"],
                            "attemptId": attempt["attemptId"],
                            "stage": attempt["stage"],
                            "audience": audience,
                            "findingId": native.finding_id,
                            "field": field,
                            "statement": statement,
                            "statementHash": digest(statement),
                            "sourceHash": request_hash,
                            "requestHash": request_hash,
                            "referenceDate": reference.isoformat() if reference else None,
                            "candidateClaimIds": refs,
                            "source": sources,
                            "candidateContext": native.model_dump(mode="json", by_alias=True),
                            "split": job.get("split", "unrecorded"),
                            "replayedProseRejected": bool(local_errors),
                            "errorKinds": sorted(
                                {
                                    kind
                                    for error in local_errors
                                    for kind in getattr(
                                        error, "error_kinds", ("unclassified_prose_guard",)
                                    )
                                }
                            ),
                        }
                    )
    body = {
        "schemaVersion": 1,
        "manifestHash": state["manifestHash"],
        "resultHash": digest(state),
        "guardRuntimeHashes": runtime_hashes(),
        "scope": "recorded-assessment-prose-only; native-decisions-and-whole-output-not-measured",
        "requests": requests,
        "units": units,
        "assessments": [],
        "unreviewableAttempts": unreviewable,
        "providerCalls": 0,
    }
    return {**body, "packetHash": digest(body)}


def measure_guard_labels(packet: dict, human: dict | None = None) -> dict:
    require(
        packet["packetHash"]
        == digest({key: value for key, value in packet.items() if key != "packetHash"}),
        "REVIEW_PACKET_CHANGED",
    )
    if packet["units"]:
        validate_packet(packet)
    labels = {}
    if human is not None:
        annotation, labels = _annotations(packet, human, "human")
        require(
            annotation.provenance is not None
            and annotation.provenance.origin == "external_human_review"
            and annotation.provenance.independent_of_judge,
            "HUMAN_PROVENANCE_REQUIRED",
        )
        units = {unit["unitId"]: unit for unit in packet["units"]}
        for judgment in annotation.judgments:
            require(
                all(
                    anchor.claim_id in units[judgment.unit_id]["candidateClaimIds"]
                    for anchor in judgment.anchors
                ),
                "ANCHOR_OUTSIDE_SELECTED_REFS",
            )
    rows = [
        {
            "unitId": unit["unitId"],
            "humanVerdict": labels.get(unit["unitId"]),
            "rejected": unit["replayedProseRejected"],
            "stage": unit["stage"],
            "split": unit["split"],
        }
        for unit in packet["units"]
    ]
    supported = [row for row in rows if row["humanVerdict"] == "SUPPORTED"]
    contradicted = [row for row in rows if row["humanVerdict"] == "CONTRADICTED"]
    return {
        "packetHash": packet["packetHash"],
        "scope": packet["scope"],
        "rows": rows,
        "humanLabeledUnits": len(labels),
        "unmeasuredUnits": len(rows) - len(labels),
        "unreviewableAttempts": len(packet["unreviewableAttempts"]),
        "labelsByVerdict": dict(Counter(labels.values())),
        "supportedFalseRejectionRate": sum(row["rejected"] for row in supported) / len(supported)
        if supported
        else None,
        "contradictionMissRate": sum(not row["rejected"] for row in contradicted)
        / len(contradicted)
        if contradicted
        else None,
        "semanticQualityMeasured": bool(labels),
        "automaticReleaseDecision": None,
        "providerCalls": 0,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("export", "measure"))
    parser.add_argument("--run", type=Path)
    parser.add_argument("--packet", type=Path)
    parser.add_argument("--human", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "export":
        require(args.run is not None, "RUN_REQUIRED")
        value = export_guard_packet(
            json.loads((args.run / "manifest.json").read_text()),
            json.loads((args.run / "result.json").read_text()),
        )
    else:
        require(args.packet is not None, "PACKET_REQUIRED")
        value = measure_guard_labels(
            json.loads(args.packet.read_text()),
            json.loads(args.human.read_text()) if args.human else None,
        )
    require(not args.output.exists(), "GUARD_OUTPUT_EXISTS")
    atomic_save(args.output, value)


if __name__ == "__main__":
    main()
