"""Build an offline blind review packet and validate human labels against it.

Hashes bind labels to the displayed inputs, detecting accidental edits or a wrong
dataset. They are not signatures or proof of human independence. This tool does
not generate labels, call a model, or measure model accuracy.
"""

import argparse
import hashlib
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

SCHEMA_VERSION = 1
PACKET_PURPOSE = "topic-relevance-blind-review"
REVIEW_PURPOSE = "topic-relevance-human-labels"
PLACEHOLDER = "__REVIEW_PACKET_JSON__"
MAX_JSON_BYTES = 10 * 1024 * 1024
MAX_CASES = 1000
MAX_SAFE_INTEGER = 2**53 - 1
DECISIONS = frozenset({"RELEVANT", "IRRELEVANT", "UNCERTAIN"})
SOURCE_FIELDS = frozenset({"datasetId", "createdAt", "cases"})
PACKET_FIELDS = SOURCE_FIELDS | {"schemaVersion", "purpose", "datasetSha256"}
CASE_FIELDS = frozenset({"caseId", "inputSha256", "topic", "article"})
TOPIC_FIELDS = frozenset(
    {"id", "name", "queryText", "requiredKeywords", "optionalKeywords", "excludedKeywords"}
)
ARTICLE_FIELDS = frozenset(
    {"id", "title", "summary", "bodyText", "publisher", "url", "publishedAt", "bodyTruncated"}
)
REVIEW_FIELDS = frozenset(
    {
        "schemaVersion", "purpose", "datasetId", "datasetSha256", "exportKind",
        "exportedAt", "records",
    }
)
RECORD_FIELDS = frozenset(
    {"caseId", "inputSha256", "decision", "reason", "updatedAt", "sourceOpened"}
)
_ISO_DATETIME = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})?\Z"
)


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      allow_nan=False)


def sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def _object(value: Any, fields: frozenset[str] | set[str], location: str) -> dict:
    if not isinstance(value, dict) or value.keys() != fields:
        raise ValueError(f"{location} must contain exactly: {', '.join(sorted(fields))}")
    return value


def _text(value: Any, location: str, maximum: int, *, nullable: bool = False,
          nonempty: bool = False) -> None:
    if nullable and value is None:
        return
    if not isinstance(value, str) or len(value) > maximum or (nonempty and not value.strip()):
        raise ValueError(f"{location} must be a string of at most {maximum} characters")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ValueError(f"{location} contains invalid Unicode") from error


def _identifier(value: Any, location: str) -> None:
    _text(value, location, 200, nonempty=True)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,199}", value):
        raise ValueError(f"{location} must be a plain ASCII identifier")


def _positive_id(value: Any, location: str) -> None:
    if type(value) is not int or not 1 <= value <= MAX_SAFE_INTEGER:
        raise ValueError(f"{location} must be a positive JavaScript-safe integer")


def _timestamp(value: Any, location: str, *, nullable: bool = False,
               timezone_required: bool = True) -> None:
    if nullable and value is None:
        return
    if not isinstance(value, str) or not _ISO_DATETIME.fullmatch(value):
        raise ValueError(f"{location} must be an ISO datetime")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{location} must be a valid ISO datetime") from error
    if timezone_required and parsed.tzinfo is None:
        raise ValueError(f"{location} must include a timezone")


def _hash(value: Any, location: str) -> None:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError(f"{location} must be a lowercase SHA-256 hash")


def _bounded_json(value: Any) -> None:
    if len(canonical_json(value).encode("utf-8")) > MAX_JSON_BYTES:
        raise ValueError("JSON exceeds the 10 MiB size limit")


def _case(case: Any, location: str, *, hashed: bool) -> None:
    _object(case, CASE_FIELDS if hashed else CASE_FIELDS - {"inputSha256"}, location)
    _identifier(case["caseId"], f"{location}.caseId")
    topic = _object(case["topic"], TOPIC_FIELDS, f"{location}.topic")
    _positive_id(topic["id"], f"{location}.topic.id")
    _text(topic["name"], f"{location}.topic.name", 200, nonempty=True)
    _text(topic["queryText"], f"{location}.topic.queryText", 500, nullable=True)
    for field in ("requiredKeywords", "optionalKeywords", "excludedKeywords"):
        keywords = topic[field]
        if not isinstance(keywords, list) or len(keywords) > 100:
            raise ValueError(f"{location}.topic.{field} must be a list of at most 100 keywords")
        for keyword in keywords:
            _text(keyword, f"{location}.topic.{field}", 100, nonempty=True)
    article = _object(case["article"], ARTICLE_FIELDS, f"{location}.article")
    _positive_id(article["id"], f"{location}.article.id")
    _text(article["title"], f"{location}.article.title", 1000, nonempty=True)
    _text(article["summary"], f"{location}.article.summary", 1000, nullable=True)
    _text(article["bodyText"], f"{location}.article.bodyText", 5000)
    _text(article["publisher"], f"{location}.article.publisher", 500)
    _text(article["url"], f"{location}.article.url", 4000, nullable=True)
    if article["url"] is not None:
        try:
            url = urlsplit(article["url"])
            if (url.scheme not in {"https", "http"} or not url.hostname
                    or url.username is not None or url.password is not None
                    or any(char.isspace() or ord(char) < 32 for char in article["url"])):
                raise ValueError("unsafe URL")
        except ValueError as error:
            raise ValueError(f"{location}.article.url must be an absolute HTTP(S) URL") from error
    _timestamp(article["publishedAt"], f"{location}.article.publishedAt", nullable=True,
               timezone_required=False)
    if type(article["bodyTruncated"]) is not bool:
        raise ValueError(f"{location}.article.bodyTruncated must be a boolean")
    if hashed:
        _hash(case["inputSha256"], f"{location}.inputSha256")
        if case["inputSha256"] != sha256({"topic": topic, "article": article}):
            raise ValueError(f"{location}.inputSha256 does not match its displayed inputs")


def _cases(cases: Any, *, hashed: bool) -> None:
    if not isinstance(cases, list) or not 1 <= len(cases) <= MAX_CASES:
        raise ValueError(f"cases must contain between 1 and {MAX_CASES} entries")
    case_ids: set[str] = set()
    pairs: set[tuple[int, int]] = set()
    for index, case in enumerate(cases):
        _case(case, f"cases[{index}]", hashed=hashed)
        pair = (case["article"]["id"], case["topic"]["id"])
        if case["caseId"] in case_ids or pair in pairs:
            raise ValueError("cases must have unique caseId and unique article/topic pairs")
        case_ids.add(case["caseId"])
        pairs.add(pair)


def build_packet(payload: dict) -> dict:
    """Validate an explicitly selected source and copy only blind display fields."""
    _object(payload, SOURCE_FIELDS, "source")
    _identifier(payload["datasetId"], "datasetId")
    _timestamp(payload["createdAt"], "createdAt")
    _cases(payload["cases"], hashed=False)
    _bounded_json(payload)
    packet = json.loads(canonical_json(payload))
    packet.update(schemaVersion=SCHEMA_VERSION, purpose=PACKET_PURPOSE)
    for case in packet["cases"]:
        case["inputSha256"] = sha256({"topic": case["topic"], "article": case["article"]})
    packet["datasetSha256"] = sha256(packet)
    validate_packet(packet)
    return packet


def validate_packet(packet: Any) -> dict:
    _object(packet, PACKET_FIELDS, "packet")
    if type(packet["schemaVersion"]) is not int or packet["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError("packet.schemaVersion must be 1")
    if packet["purpose"] != PACKET_PURPOSE:
        raise ValueError("packet.purpose is not a blind topic relevance review")
    _identifier(packet["datasetId"], "datasetId")
    _timestamp(packet["createdAt"], "createdAt")
    _cases(packet["cases"], hashed=True)
    _hash(packet["datasetSha256"], "datasetSha256")
    if packet["datasetSha256"] != sha256(
        {key: value for key, value in packet.items() if key != "datasetSha256"}
    ):
        raise ValueError("datasetSha256 does not match the packet")
    _bounded_json(packet)
    return packet


def render_html(packet: dict, template: str | None = None) -> str:
    validate_packet(packet)
    if template is None:
        template = (Path(__file__).parent / "templates/topic_relevance_review.html").read_text(
            encoding="utf-8"
        )
    if template.count(PLACEHOLDER) != 1:
        raise ValueError("The review template must contain exactly one packet placeholder")
    embedded = canonical_json(packet)
    for literal, escaped in (("&", "\\u0026"), ("<", "\\u003c"), (">", "\\u003e"),
                             ("\u2028", "\\u2028"), ("\u2029", "\\u2029")):
        embedded = embedded.replace(literal, escaped)
    return template.replace(PLACEHOLDER, embedded)


def validate_review(packet: dict, review: Any, *, require_complete: bool = False) -> dict:
    """Check a returned export and report annotation counts, never model accuracy."""
    validate_packet(packet)
    _object(review, REVIEW_FIELDS, "review")
    if type(review["schemaVersion"]) is not int or review["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError("review.schemaVersion must be 1")
    if review["purpose"] != REVIEW_PURPOSE:
        raise ValueError("review.purpose is not human topic relevance labels")
    for field in ("datasetId", "datasetSha256"):
        if review[field] != packet[field]:
            raise ValueError(f"review.{field} does not match the original packet")
    if review["exportKind"] not in ("draft", "final"):
        raise ValueError("review.exportKind must be draft or final")
    _timestamp(review["exportedAt"], "exportedAt")
    records = review["records"]
    if not isinstance(records, list) or len(records) != len(packet["cases"]):
        raise ValueError("records must contain every packet case exactly once")
    expected = {case["caseId"]: case["inputSha256"] for case in packet["cases"]}
    seen: set[str] = set()
    counts: Counter[str] = Counter()
    source_opened = 0
    for index, record in enumerate(records):
        location = f"records[{index}]"
        _object(record, RECORD_FIELDS, location)
        _identifier(record["caseId"], f"{location}.caseId")
        case_id = record["caseId"]
        if case_id in seen or case_id not in expected:
            raise ValueError(f"{location}.caseId is unknown or duplicated")
        seen.add(case_id)
        if record["inputSha256"] != expected[case_id]:
            raise ValueError(f"{location}.inputSha256 does not match the original input")
        decision = record["decision"]
        if decision is not None and (not isinstance(decision, str) or decision not in DECISIONS):
            raise ValueError(f"{location}.decision must be RELEVANT, IRRELEVANT, UNCERTAIN or null")
        _text(record["reason"], f"{location}.reason", 2000)
        _timestamp(record["updatedAt"], f"{location}.updatedAt", nullable=decision is None)
        if type(record["sourceOpened"]) is not bool:
            raise ValueError(f"{location}.sourceOpened must be a boolean")
        source_opened += record["sourceOpened"]
        counts[decision or "blank"] += 1
    if (require_complete or review["exportKind"] == "final") and counts["blank"]:
        raise ValueError("A complete review requires a decision for every case")
    _bounded_json(review)
    return {
        "datasetId": packet["datasetId"],
        "datasetSha256": packet["datasetSha256"],
        "exportKind": review["exportKind"],
        "total": len(records),
        "decisions": {key: counts[key] for key in sorted(DECISIONS)},
        "blank": counts["blank"],
        "externalSourceOpened": source_opened,
        "complete": counts["blank"] == 0,
    }


def _unique_object(pairs: list[tuple[str, Any]]) -> dict:
    result: dict = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError(f"Non-finite JSON number is not allowed: {value}")


def read_json(path: Path) -> Any:
    with path.open("rb") as source:
        raw = source.read(MAX_JSON_BYTES + 1)
    if len(raw) > MAX_JSON_BYTES:
        raise ValueError("JSON exceeds the 10 MiB size limit")
    return json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                      parse_constant=_reject_constant)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    build = subparsers.add_parser("build", help="Create a new offline blind review directory")
    build.add_argument("--source", type=Path, required=True)
    build.add_argument("--output-dir", type=Path, required=True)
    validate = subparsers.add_parser("validate", help="Validate a returned human review JSON")
    validate.add_argument("--packet", type=Path, required=True)
    validate.add_argument("--review", type=Path, required=True)
    validate.add_argument("--require-complete", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.command == "build":
            packet = build_packet(read_json(args.source))
            html = render_html(packet)
            args.output_dir.mkdir(parents=True, exist_ok=False)
            (args.output_dir / "packet.json").write_text(
                json.dumps(packet, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                encoding="utf-8",
            )
            (args.output_dir / "review.html").write_text(html, encoding="utf-8")
            print(json.dumps({"datasetId": packet["datasetId"], "cases": len(packet["cases"]),
                              "reviewHtml": str(args.output_dir / "review.html")},
                             ensure_ascii=False))
        else:
            summary = validate_review(read_json(args.packet), read_json(args.review),
                                      require_complete=args.require_complete)
            print(json.dumps(summary, ensure_ascii=False, indent=2))
    except (ValueError, OSError, UnicodeError, RecursionError) as error:
        parser.exit(2, f"error: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
