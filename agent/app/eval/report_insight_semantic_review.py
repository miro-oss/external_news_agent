"""Offline, source-bound human/judge comparison; never invokes a judge or provider.

Export current-service outputs, annotate exact source offsets, then compare saved
three-class judgments. An absent label is unmeasured, never a pass or abstention.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.eval.report_insight_review import QUALITY_RUBRIC
from app.eval.report_insight_run import atomic_save, digest, require
from app.schemas.report_insight import ReportInsightRequest

Verdict = Literal["SUPPORTED", "CONTRADICTED", "INSUFFICIENT"]
VERDICTS = ("SUPPORTED", "CONTRADICTED", "INSUFFICIENT")
AXES = ("directness", "impact", "urgency")


class ClosedModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class SourceAnchor(ClosedModel):
    claim_id: str
    sentence_id: int = Field(ge=0)
    start: int = Field(ge=0)
    end: int = Field(gt=0)
    quote: str = Field(min_length=1)


class Judgment(ClosedModel):
    unit_id: str
    verdict: Verdict
    anchors: list[SourceAnchor]


class AnnotationProvenance(ClosedModel):
    """Reviewer attestation, not a cryptographic proof of human authorship."""

    origin: Literal["external_human_review", "model_generated"]
    reference: str = Field(min_length=1)
    reviewed_at: str = Field(min_length=1)
    independent_of_judge: bool
    source_presentation: Literal["original_sentences", "faithful_condensed_sources"] = (
        "original_sentences"
    )
    anchor_origin: Literal["reviewer_offsets", "system_context_mapping"] = "reviewer_offsets"


class AssessmentExpectation(ClosedModel):
    assessment_id: str
    decidable_axes: list[Literal["directness", "impact", "urgency"]]
    required_claim_ids: list[str]


class AnnotationSet(ClosedModel):
    packet_hash: str
    kind: Literal["human", "judge"]
    annotator: str = Field(min_length=1)
    judgments: list[Judgment]
    # Human expectations are not passed to production generation or judge inputs.
    assessments: list[AssessmentExpectation] = Field(default_factory=list)
    provenance: AnnotationProvenance | None = None


def export_packet(manifest: dict, state: dict) -> dict:
    require(state["manifestHash"] == digest(manifest), "MANIFEST_CHANGED")
    units, assessments = [], []
    for job in state["results"]:
        if job["status"] != "success":
            continue
        request = ReportInsightRequest.model_validate(
            manifest["cases"][job["caseIndex"]]["request"]
        )
        claims, finding_refs = {}, {}
        for finding in request.findings:
            sentences = {sentence.index: sentence.text for sentence in finding.sentences}
            finding_refs[finding.id] = [claim.id for claim in finding.claims]
            for claim in finding.claims:
                claims[claim.id] = {
                    "claimId": claim.id,
                    "claimText": claim.text,
                    "claimType": claim.claim_type,
                    "attributedTo": claim.attributed_to,
                    "sentences": [
                        {"sentenceId": index, "text": sentences[index]}
                        for index in claim.evidence_sentence_ids
                    ],
                }

        source_hash = digest(request.model_dump(mode="json"))

        def add(
            field,
            statement,
            refs,
            context,
            scope=None,
            *,
            claims=claims,
            job=job,
            source_hash=source_hash,
        ):
            require(set(refs) <= set(claims), "CANDIDATE_UNKNOWN_CLAIM")
            sources = refs if scope is None else scope
            units.append(
                {
                    "unitId": f"{job['jobId']}:{field}",
                    "jobId": job["jobId"],
                    "audience": job["audience"],
                    "field": field,
                    "statement": statement,
                    "statementHash": digest(statement),
                    "candidateContext": context,
                    "sourceHash": source_hash,
                    "candidateClaimIds": refs,
                    "source": [claims[ref] for ref in sources],
                }
            )

        for insight in job["response"]["insights"]:
            require(insight["audience"] == job["audience"], "CANDIDATE_AUDIENCE_MISMATCH")
            headline_refs = list(
                dict.fromkeys(ref for a in insight["assessments"] for ref in a["basisClaimIds"])
            )
            add("headline", insight["headline"], headline_refs, {"overview": insight["overview"]})
            for assessment in insight["assessments"]:
                finding_id = assessment["findingId"]
                field = f"assessments[{finding_id}]"
                require(finding_id in finding_refs, "CANDIDATE_UNKNOWN_FINDING")
                add(
                    f"{field}.reason",
                    assessment["reason"],
                    assessment["basisClaimIds"],
                    assessment,
                    finding_refs[finding_id],
                )
                assessments.append(
                    {
                        "assessmentId": f"{job['jobId']}:{field}",
                        "axes": assessment["axes"],
                        "basisClaimIds": assessment["basisClaimIds"],
                        "availableClaimIds": finding_refs[finding_id],
                    }
                )
            for group, fields in (
                ("overview", ("text", "assumption")),
                ("implications", ("text", "mechanism", "assumption", "falsifiedBy")),
                ("watchItems", ("topic", "indicator", "trigger")),
            ):
                for index, item in enumerate(insight[group]):
                    for field in fields:
                        add(f"{group}[{index}].{field}", item[field], item["basisClaimIds"], item)
    packet = {
        "schemaVersion": 1,
        "manifestHash": state["manifestHash"],
        "resultHash": digest(state),
        "promptVersion": manifest["promptVersion"],
        "qualityRubric": QUALITY_RUBRIC,
        "units": units,
        "assessments": assessments,
        "verdictDefinitions": {
            "SUPPORTED": "Source supports the statement, attribution and conditions.",
            "CONTRADICTED": "Quoted source directly contradicts the statement.",
            "INSUFFICIENT": "The supplied source cannot establish support or contradiction.",
        },
        "limitations": (
            "Exact quote checks verify provenance, not semantic correctness; human labels required."
        ),
    }
    return {**packet, "packetHash": digest(packet)}


def _annotations(packet: dict, raw: dict, kind: str):
    annotation = AnnotationSet.model_validate(raw)
    require(annotation.kind == kind, "ANNOTATION_KIND_MISMATCH")
    require(annotation.packet_hash == packet["packetHash"], "ANNOTATION_PACKET_MISMATCH")
    if annotation.provenance is not None:
        require(
            (annotation.provenance.origin == "external_human_review") == (kind == "human"),
            "ANNOTATION_PROVENANCE_KIND_MISMATCH",
        )
    units = {unit["unitId"]: unit for unit in packet["units"]}
    labels = {}
    for judgment in annotation.judgments:
        require(
            judgment.unit_id in units and judgment.unit_id not in labels,
            "UNKNOWN_OR_DUPLICATE_UNIT",
        )
        source = {item["claimId"]: item for item in units[judgment.unit_id]["source"]}
        require(
            judgment.verdict == "INSUFFICIENT" or bool(judgment.anchors), "SOURCE_ANCHOR_REQUIRED"
        )
        for anchor in judgment.anchors:
            require(anchor.claim_id in source, "ANCHOR_CLAIM_OUTSIDE_SOURCE")
            sentences = {
                item["sentenceId"]: item["text"] for item in source[anchor.claim_id]["sentences"]
            }
            require(anchor.sentence_id in sentences, "ANCHOR_SENTENCE_OUTSIDE_SOURCE")
            sentence = sentences[anchor.sentence_id]
            require(
                anchor.start < anchor.end <= len(sentence)
                and sentence[anchor.start : anchor.end] == anchor.quote,
                "ANCHOR_QUOTE_MISMATCH",
            )
        labels[judgment.unit_id] = judgment.verdict
    require(kind == "human" or not annotation.assessments, "ONLY_HUMANS_LABEL_ASSESSMENTS")
    return annotation, labels


def compare_annotations(packet: dict, human: dict, judge: dict) -> dict:
    require(
        packet["packetHash"]
        == digest({key: value for key, value in packet.items() if key != "packetHash"}),
        "REVIEW_PACKET_CHANGED",
    )
    human_set, truth = _annotations(packet, human, "human")
    _, predictions = _annotations(packet, judge, "judge")
    matrix = {truth: dict.fromkeys(VERDICTS, 0) for truth in VERDICTS}
    for identifier in truth.keys() & predictions.keys():
        matrix[truth[identifier]][predictions[identifier]] += 1
    paired = len(truth.keys() & predictions.keys())
    assessments = {item["assessmentId"]: item for item in packet["assessments"]}
    seen = set()
    decidable = unnecessary = required = missing = 0
    for expectation in human_set.assessments:
        identifier = expectation.assessment_id
        require(
            identifier in assessments and identifier not in seen, "UNKNOWN_OR_DUPLICATE_ASSESSMENT"
        )
        seen.add(identifier)
        assessment = assessments[identifier]
        require(
            len(expectation.decidable_axes) == len(set(expectation.decidable_axes))
            and len(expectation.required_claim_ids) == len(set(expectation.required_claim_ids)),
            "DUPLICATE_EXPECTATION",
        )
        require(
            set(expectation.required_claim_ids) <= set(assessment["availableClaimIds"]),
            "EXPECTED_CLAIM_OUTSIDE_FINDING",
        )
        decidable += len(expectation.decidable_axes)
        unnecessary += sum(assessment["axes"][axis] is None for axis in expectation.decidable_axes)
        required += len(expectation.required_claim_ids)
        missing += len(set(expectation.required_claim_ids) - set(assessment["basisClaimIds"]))
    false_support = sum(matrix[label]["SUPPORTED"] for label in ("CONTRADICTED", "INSUFFICIENT"))
    predicted_support = sum(matrix[label]["SUPPORTED"] for label in VERDICTS)
    return {
        "packetHash": packet["packetHash"],
        "unitCount": len(packet["units"]),
        "humanLabeledUnits": len(truth),
        "judgeLabeledUnits": len(predictions),
        "pairedUnits": paired,
        "missingJudgeUnits": len(truth.keys() - predictions.keys()),
        "unlabeledHumanUnits": len(packet["units"]) - len(truth),
        "confusionMatrix": matrix,
        "agreementRate": sum(matrix[label][label] for label in VERDICTS) / paired
        if paired
        else None,
        "falseSupportCount": false_support,
        "falseSupportAmongPredictedSupport": false_support / predicted_support
        if predicted_support
        else None,
        "humanAssessmentCount": len(seen),
        "humanDecidableAxes": decidable,
        "unnecessaryAbstentionCount": unnecessary,
        "unnecessaryAbstentionRate": unnecessary / decidable if decidable else None,
        "requiredEvidenceCount": required,
        "missingRequiredEvidenceCount": missing,
        "evidenceOmissionRate": missing / required if required else None,
        "automaticReleaseDecision": None,
        "providerCalls": 0,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("export", "compare"))
    parser.add_argument("--run", type=Path)
    parser.add_argument("--packet", type=Path)
    parser.add_argument("--human", type=Path)
    parser.add_argument("--judge", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    def read(path):
        return json.loads(path.read_text())

    if args.command == "export":
        require(args.run is not None, "RUN_REQUIRED")
        value = export_packet(read(args.run / "manifest.json"), read(args.run / "result.json"))
    else:
        require(all((args.packet, args.human, args.judge)), "PACKET_AND_ANNOTATIONS_REQUIRED")
        value = compare_annotations(read(args.packet), read(args.human), read(args.judge))
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    atomic_save(args.output, value)


if __name__ == "__main__":
    main()
