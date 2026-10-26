"""Score frozen relevance outputs against human labels without calling a provider.

Input binding establishes comparability, not independence or production acceptance.
Missing and failed requests remain in planned-label denominators. Human uncertainty
is reported separately and never converted to a decisive reference label.
"""

import argparse
import hashlib
import json
import math
import re
from collections import Counter
from pathlib import Path
from typing import Any

from app.eval.topic_relevance_review import read_json, validate_packet, validate_review

LABELS = ("RELEVANT", "IRRELEVANT", "UNCERTAIN")
_DOCS_ROOT = Path(__file__).resolve().parents[3] / "docs"
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def wilson(numerator: int, denominator: int) -> dict[str, Any]:
    """Descriptive Wilson interval; correlated articles are not independent trials."""
    if not denominator:
        return {"numerator": numerator, "denominator": 0, "rate": None, "wilson95": None}
    rate = numerator / denominator
    z = 1.959963984540054
    divisor = 1 + z * z / denominator
    center = (rate + z * z / (2 * denominator)) / divisor
    half = z * math.sqrt(
        rate * (1 - rate) / denominator + z * z / (4 * denominator * denominator)
    ) / divisor
    return {
        "numerator": numerator, "denominator": denominator, "rate": rate,
        "wilson95": {"lower": max(0, center - half), "upper": min(1, center + half)},
    }


def _predictions(cases: dict, results: dict) -> tuple[dict, dict, dict]:
    for name in ("promptVersion", "model"):
        _require(isinstance(results.get(name), str) and bool(results[name].strip()),
                 f"Missing result {name}")
    _require(isinstance(results.get("promptSha256"), str)
             and bool(_SHA256.fullmatch(results["promptSha256"])), "Invalid prompt hash")
    _require(results.get("inFlight") is None, "Unresolved in-flight request")
    _require(isinstance(results.get("batches"), list), "Results batches must be a list")
    _require(isinstance(results.get("errors", []), list), "Results errors must be a list")
    predictions, failures, phases = {}, {}, {}
    seen, batch_ids, providers = set(), set(), set()
    records = [(row, False) for row in results["batches"]]
    records.extend((row, True) for row in results.get("errors", []))
    for record, failed in records:
        _require(isinstance(record, dict), "Invalid result record")
        bid = record.get("batchId")
        _require((type(bid) is int and bid > 0)
                 or (isinstance(bid, str) and bool(bid.strip())), "Invalid batch ID")
        _require(str(bid) not in batch_ids, "Duplicate batch ID")
        batch_ids.add(str(bid))
        ids = record.get("caseIds")
        _require(isinstance(ids, list) and 1 <= len(ids) <= 10
                 and all(isinstance(cid, str) for cid in ids), "Invalid batch cases")
        _require(len(set(ids)) == len(ids) and set(ids) <= cases.keys()
                 and not seen.intersection(ids), "Unknown or duplicate model cases")
        seen.update(ids)
        _require(record.get("inputSha256s") == {cid: cases[cid]["inputSha256"] for cid in ids},
                 "Model input hashes mismatch")
        _require(len({cases[cid]["topic"]["id"] for cid in ids}) == 1, "Mixed batch topics")
        phase = record.get("phase")
        _require(isinstance(phase, str) and bool(phase.strip()), "Missing batch phase")
        phases.update(dict.fromkeys(ids, phase))
        article_cases = {cases[cid]["article"]["id"]: cid for cid in ids}
        _require(len(article_cases) == len(ids), "Ambiguous batch article IDs")
        response = record.get("response")
        _require(not (response is not None and (failed or record.get("error"))),
                 "Failed batch cannot have a response")
        if response is None:
            failures.update({cid: {"batchId": bid, "kind": "REQUEST_FAILED"} for cid in ids})
            continue
        _require(isinstance(response, dict), "Invalid response")
        meta = response.get("meta")
        _require(isinstance(meta, dict), "Missing response metadata")
        _require(meta.get("mock") is False and meta.get("truncated") is False,
                 "Mock or truncated response cannot be scored")
        for name in ("promptVersion", "model"):
            _require(meta.get(name) == results[name], f"Response {name} mismatch")
        if "promptSha256" in meta:
            _require(meta["promptSha256"] == results["promptSha256"],
                     "Response prompt hash mismatch")
        provider = meta.get("provider")
        _require(isinstance(provider, str) and bool(provider.strip()), "Missing response provider")
        providers.add(provider)
        if "provider" in results:
            _require(provider == results["provider"], "Response provider mismatch")
        decisions = response.get("decisions")
        _require(isinstance(decisions, list), "Missing decisions")
        returned = set()
        for decision in decisions:
            _require(isinstance(decision, dict), "Invalid decision")
            aid = decision.get("articleId")
            _require(type(aid) is int and aid in article_cases and aid not in returned,
                     "Unknown or duplicate response article")
            returned.add(aid)
            cid = article_cases[aid]
            _require(decision.get("status") in LABELS, "Invalid decision status")
            reason = decision.get("reason")
            _require(isinstance(reason, str) and bool(reason.strip()) and len(reason) <= 500,
                     "Invalid decision reason")
            quotes = decision.get("evidenceQuotes")
            _require(isinstance(quotes, list) and 1 <= len(quotes) <= 3,
                     "Every decision requires evidence quotes")
            article = cases[cid]["article"]
            for quote in quotes:
                _require(isinstance(quote, str) and bool(quote.strip()) and len(quote) <= 300
                         and any(quote in (article[name] or "")
                                 for name in ("title", "summary", "bodyText")),
                         "Unbound evidence quote")
            predictions[cid] = decision
        failures.update({article_cases[aid]: {"batchId": bid, "kind": "MISSING_DECISION"}
                         for aid in article_cases.keys() - returned})
    _require(len(providers) <= 1, "Mixed providers must be scored separately")
    return predictions, failures, phases


def _metrics(ids: list[str], humans: dict, predictions: dict, failures: dict) -> dict:
    counts = Counter(humans[cid]["decision"] for cid in ids)
    confusion = {human: dict.fromkeys(LABELS, 0) for human in LABELS}
    for cid in ids:
        if cid in predictions:
            confusion[humans[cid]["decision"]][predictions[cid]["status"]] += 1

    def outcomes(label: str) -> dict:
        cells = confusion[label]
        return {
            "accepted": cells["RELEVANT"], "rejected": cells["IRRELEVANT"],
            "heldUncertain": cells["UNCERTAIN"], "denominator": counts[label],
            "failed": sum(cid in failures and humans[cid]["decision"] == label for cid in ids),
            "missing": sum(cid not in predictions and cid not in failures
                           and humans[cid]["decision"] == label for cid in ids),
        }

    relevant, irrelevant = outcomes("RELEVANT"), outcomes("IRRELEVANT")
    covered = sum(cid in predictions for cid in ids)
    failed = sum(cid in failures for cid in ids)
    return {
        "total": len(ids), "covered": covered, "failed": failed,
        "missing": len(ids) - covered - failed,
        "humanCounts": {label: counts[label] for label in LABELS},
        "confusionRowsHumanColumnsModel": confusion,
        "relevantOutcomes": relevant, "irrelevantOutcomes": irrelevant,
        "humanUncertainOutcomes": outcomes("UNCERTAIN"),
        "humanUncertainModelCounts": confusion["UNCERTAIN"],
        "relevantRetentionAllPlanned": wilson(relevant["accepted"], counts["RELEVANT"]),
        "relevantRejectionAllPlanned": wilson(relevant["rejected"], counts["RELEVANT"]),
        "relevantHoldAllPlanned": wilson(relevant["heldUncertain"], counts["RELEVANT"]),
        "irrelevantRejectionAllPlanned": wilson(irrelevant["rejected"], counts["IRRELEVANT"]),
        "irrelevantPassAllPlanned": wilson(irrelevant["accepted"], counts["IRRELEVANT"]),
        "irrelevantHoldAllPlanned": wilson(irrelevant["heldUncertain"], counts["IRRELEVANT"]),
        "irrelevantNonAcceptanceAllPlanned": wilson(
            irrelevant["rejected"] + irrelevant["heldUncertain"], counts["IRRELEVANT"]),
        "acceptedPrecisionDecisiveHumans": wilson(
            relevant["accepted"], relevant["accepted"] + irrelevant["accepted"]),
        "successfulRequestCoverage": wilson(covered, len(ids)),
    }


def evaluate(packet: dict, review: dict, manifest: dict, results: dict) -> dict:
    """Return descriptive metrics only, without mutating or generating human labels."""
    validate_packet(packet)
    validate_review(packet, review, require_complete=True)
    _require(review["exportKind"] == "final", "A final human review is required")
    _require(isinstance(manifest, dict) and isinstance(results, dict), "Invalid scoring inputs")
    cases = {case["caseId"]: case for case in packet["cases"]}
    humans = {row["caseId"]: row for row in review["records"]}
    _require(manifest.get("datasetId") == packet["datasetId"], "Manifest dataset mismatch")
    if "datasetSha256" in manifest:
        _require(manifest["datasetSha256"] == packet["datasetSha256"], "Manifest hash mismatch")
    rows = manifest.get("cases")
    _require(isinstance(rows, list) and all(isinstance(row, dict)
             and isinstance(row.get("caseId"), str) for row in rows), "Invalid manifest cases")
    indexed = {row["caseId"]: row for row in rows}
    _require(len(indexed) == len(rows) and indexed.keys() == cases.keys(),
             "Manifest cases mismatch")
    for cid, case in cases.items():
        row = indexed[cid]
        _require(type(row.get("articleId")) is int and row["articleId"] == case["article"]["id"]
                 and type(row.get("topicId")) is int and row["topicId"] == case["topic"]["id"],
                 "Manifest article/topic mismatch")
        _require(isinstance(row.get("stratum"), str) and bool(row["stratum"].strip()),
                 "Missing manifest stratum")
        if "inputSha256" in row:
            _require(row["inputSha256"] == case["inputSha256"], "Manifest input hash mismatch")
    for name in ("datasetId", "datasetSha256"):
        _require(results.get(name) == packet[name], f"Model {name} mismatch")
    for name in ("model", "promptVersion", "promptSha256", "plan"):
        if name in manifest:
            _require(results.get(name) == manifest[name], f"Frozen manifest {name} mismatch")
    predictions, failures, phases = _predictions(cases, results)

    def metrics(ids):
        return _metrics(list(ids), humans, predictions, failures)

    def brief(cid):
        case = cases[cid]
        predicted = predictions.get(cid)
        return {
            "caseId": cid, "inputSha256": case["inputSha256"],
            "articleId": case["article"]["id"], "topicId": case["topic"]["id"],
            "stratum": indexed[cid]["stratum"], "human": humans[cid]["decision"],
            "model": predicted["status"] if predicted else None,
            "failure": failures.get(cid),
        }

    return {
        "schemaVersion": 1, "purpose": "topic-relevance-offline-score",
        "evaluationScope": "DESCRIPTIVE_COMPARISON_ONLY",
        "datasetId": packet["datasetId"], "datasetSha256": packet["datasetSha256"],
        "provenance": {name: results.get(name) for name in (
            "model", "promptVersion", "promptSha256", "plan", "codeCommit",
            "runtimeSourceSha256s", "runnerSha256", "scheduleSha256")},
        "complete": len(predictions) == len(cases), "overall": metrics(cases),
        "byStratum": {
            stratum: metrics(cid for cid in cases if indexed[cid]["stratum"] == stratum)
            for stratum in sorted({row["stratum"] for row in rows})},
        "byTopic": [{
            "topicId": tid,
            **metrics(cid for cid in cases if cases[cid]["topic"]["id"] == tid),
            "byStratum": {
                stratum: metrics(cid for cid in cases if cases[cid]["topic"]["id"] == tid
                                 and indexed[cid]["stratum"] == stratum)
                for stratum in sorted({row["stratum"] for row in rows if row["topicId"] == tid})},
        } for tid in sorted({case["topic"]["id"] for case in cases.values()})],
        "byPhase": {phase: metrics(cid for cid in cases if phases.get(cid) == phase)
                    for phase in sorted(set(phases.values()))},
        "disagreements": [brief(cid) for cid in cases if cid in predictions
                          and humans[cid]["decision"] != predictions[cid]["status"]],
        "humanUncertainCases": [brief(cid) for cid in cases
                                if humans[cid]["decision"] == "UNCERTAIN"],
        "failedCases": [brief(cid) for cid in cases if cid in failures],
        "missingCases": [brief(cid) for cid in cases if cid not in predictions
                         and cid not in failures],
        "limitations": [
            "Natural and challenge strata are separate; their pooled rate is descriptive only.",
            "Failed/missing cases remain in planned denominators; neither is model UNCERTAIN.",
            "Human UNCERTAIN cases are separate and excluded from decisive-label precision.",
            "Input hashes do not establish unseen data, independent labels, or deployment quality.",
            "Wilson intervals are descriptive; topic-balanced or correlated articles may not "
            "represent the production population or independent trials.",
        ],
    }


score = evaluate


def _gate(summary: dict, args: argparse.Namespace) -> dict | None:
    targets = {"relevantRetentionAllPlanned": args.min_retention,
               "irrelevantRejectionAllPlanned": args.min_rejection}
    if all(target is None for target in targets.values()):
        return None
    _require(args.gate_stratum in summary["byStratum"], "Gate stratum is absent")
    _require(args.minimum_class_count >= 1, "Minimum class count must be positive")
    gates = {}
    for name, target in targets.items():
        if target is None:
            continue
        _require(math.isfinite(target) and 0 <= target <= 1, "Gate target must be within [0, 1]")
        metric = summary["byStratum"][args.gate_stratum][name]
        status = ("INCONCLUSIVE" if metric["denominator"] < args.minimum_class_count
                  else "PASS" if metric["rate"] >= target else "FAIL")
        gates[name] = {"status": status, "target": target,
                       "minimumDenominator": args.minimum_class_count, **metric}
    return {"stratum": args.gate_stratum, "metrics": gates,
            "interpretation": "Caller-supplied point-estimate checks; not deployment acceptance."}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("packet", "review", "manifest", "results", "output-dir"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--min-retention", type=float)
    parser.add_argument("--min-rejection", type=float)
    parser.add_argument("--minimum-class-count", type=int, default=20)
    parser.add_argument("--gate-stratum", default="natural")
    args = parser.parse_args(argv)
    try:
        output = args.output_dir.resolve()
        _require(output.is_relative_to(_DOCS_ROOT.resolve()) and output != _DOCS_ROOT.resolve(),
                 "Generated evaluation output must be under repository docs/")
        paths = {name: getattr(args, name) for name in ("packet", "review", "manifest", "results")}
        summary = evaluate(**{name: read_json(path) for name, path in paths.items()})
        gates = _gate(summary, args)
        if gates is not None:
            summary["requestedGates"] = gates
        summary["sourceFileSha256s"] = {
            name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in paths.items()}
        rendered = json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        output.mkdir(parents=True, exist_ok=False)
        (output / "evaluation-summary.json").write_text(rendered, encoding="utf-8")
        print(json.dumps({"datasetId": summary["datasetId"], "complete": summary["complete"],
                          "output": str(output / "evaluation-summary.json")}))
        failed_gate = bool(gates and any(item["status"] != "PASS"
                                        for item in gates["metrics"].values()))
        return 1 if not summary["complete"] or failed_gate else 0
    except (OSError, ValueError, TypeError, KeyError, UnicodeError, RecursionError) as error:
        parser.exit(2, f"error: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
