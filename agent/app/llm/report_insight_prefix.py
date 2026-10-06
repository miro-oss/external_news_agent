"""Recognize only a native record prefix ending at a complete JSON object."""

import json
import math


def closed_assessment_prefix(
    raw: str, audience: str, finding_ids: list[int]
) -> dict[str, dict] | None:
    """Read an ordered, strict subset; never complete a value or add a record.

    Recovery is limited to EOF after a closed finding object and JSON whitespace.
    An unfinished key/value, trailing comma, extra key or closed envelope keeps
    the ordinary full-repair path. The caller must validate every retained item.
    """
    if (
        len(raw) > 128_000
        or len(finding_ids) < 2
        or len(finding_ids) != len(set(finding_ids))
        or any(type(identifier) is not int or identifier <= 0 for identifier in finding_ids)
    ):
        return None

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result

    def reject_constant(_):
        raise ValueError("Non-finite JSON number")

    def finite_float(text):
        result = float(text)
        if not math.isfinite(result):
            raise ValueError("Non-finite JSON number")
        return result

    decoder = json.JSONDecoder(
        object_pairs_hook=unique, parse_constant=reject_constant, parse_float=finite_float
    )
    position = 0

    def whitespace():
        nonlocal position
        while position < len(raw) and raw[position] in " \t\r\n":
            position += 1

    def token(expected):
        nonlocal position
        whitespace()
        if not raw.startswith(expected, position):
            raise ValueError("Unexpected JSON envelope")
        position += len(expected)

    def value():
        nonlocal position
        whitespace()
        result, position = decoder.raw_decode(raw, position)
        return result

    try:
        token("{")
        if value() != "assessments":
            return None
        token(":")
        token("{")
        if value() != audience:
            return None
        token(":")
        token("{")
        records = {}
        for identifier in finding_ids:
            key = value()
            if key != f"finding{identifier}":
                return None
            token(":")
            record = value()
            if (
                not isinstance(record, dict)
                or type(record.get("findingId")) is not int
                or record["findingId"] != identifier
            ):
                return None
            records[key] = record
            whitespace()
            if position == len(raw):
                return records if len(records) < len(finding_ids) else None
            token(",")
    except (ValueError, RecursionError):
        return None
    return None
