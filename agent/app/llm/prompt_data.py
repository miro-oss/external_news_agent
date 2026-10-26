"""Preserve untrusted text without letting it create prompt delimiter tags."""

import json


def escape_prompt_text(value: str) -> str:
    # This protects the framing only; semantic prompt injection still requires
    # system instructions, output validation, and server-side execution guards.
    return value.replace("<", "\\u003c").replace(">", "\\u003e")


def prompt_json(value: object) -> str:
    return escape_prompt_text(json.dumps(value, ensure_ascii=False))
