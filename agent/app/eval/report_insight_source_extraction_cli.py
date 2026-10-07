"""Prepare offline extraction packets, import responses, or inspect local coverage.

This CLI makes no provider calls. The separately injected extraction provider's
caller owns billing limits; importing a response always reruns literal checks.
"""

import argparse
import json
from pathlib import Path

from app.llm.report_insight_fact_index import prompt_fact_index
from app.llm.report_insight_source_extraction import (
    EXTRACTION_VERSION,
    attach_source_proposals,
    load_source_proposal_cache,
    prepare_source_extraction_batches,
    save_source_proposal_cache,
    source_content_hash,
    validate_source_extraction,
)
from app.schemas.report_insight import ReportInsightRequest


def main(argv=None) -> dict:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "import", "inspect"))
    parser.add_argument("--request", required=True, type=Path)
    parser.add_argument("--model", required=True)
    parser.add_argument("--provider", default="openai")
    parser.add_argument("--batch-size", type=int, default=6)
    parser.add_argument("--evidence-id", action="append")
    parser.add_argument("--batch-index", type=int, default=0)
    parser.add_argument("--response", type=Path)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    source = ReportInsightRequest.model_validate_json(args.request.read_text(encoding="utf-8"))
    if args.operation == "inspect":
        if args.cache_dir is None:
            parser.error("inspect requires --cache-dir")
        bundle = load_source_proposal_cache(
            source, args.cache_dir, model=args.model, provider_name=args.provider
        )
        attach_source_proposals(source, bundle)
        payload = {
            "version": EXTRACTION_VERSION,
            "sourceContentSha256": source_content_hash(source),
            "coverage": bundle.coverage(),
            "index": prompt_fact_index(source),
        }
    else:
        batches = prepare_source_extraction_batches(
            source,
            model=args.model,
            provider_name=args.provider,
            max_sentences=args.batch_size,
            evidence_ids=args.evidence_id,
        )
        if args.operation == "prepare":
            if args.output is None:
                parser.error("prepare requires --output for local source packets")
            payload = {
                "version": EXTRACTION_VERSION,
                "sourceContentSha256": source_content_hash(source),
                "batches": [
                    {
                        "batchKey": batch.cache_key,
                        "manifest": batch.manifest,
                        "systemInstruction": batch.system_instruction,
                        "prompt": batch.prompt,
                        "responseSchema": batch.response_schema,
                    }
                    for batch in batches
                ],
            }
        else:
            if args.response is None or args.cache_dir is None:
                parser.error("import requires --response and --cache-dir")
            if not 0 <= args.batch_index < len(batches):
                parser.error("batch index is not in the prepared source selection")
            batch = batches[args.batch_index]
            result = validate_source_extraction(batch, args.response.read_text(encoding="utf-8"))
            saved = save_source_proposal_cache(args.cache_dir, batch, result)
            payload = {
                "version": EXTRACTION_VERSION,
                "cacheObject": str(saved),
                "sourceSentencesInBatch": len(batch.records),
                "roleProposals": len(result.proposals),
                "semanticallyVerifiedProposals": 0,
            }
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    # Operational stdout reports counts only; raw source packets are local files.
    summary = {key: value for key, value in payload.items() if key not in {"batches", "index"}}
    if "batches" in payload:
        summary["preparedBatches"] = len(payload["batches"])
    print(json.dumps(summary, ensure_ascii=False))
    return payload


if __name__ == "__main__":
    main()
