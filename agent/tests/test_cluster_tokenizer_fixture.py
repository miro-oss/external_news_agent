import json
from pathlib import Path

from app.core.evidence import _tokens


def test_shared_java_tokenization_fixture() -> None:
    fixture_path = (
        Path(__file__).resolve().parents[2]
        / "BE"
        / "src"
        / "test"
        / "resources"
        / "golden"
        / "tokenization.v1.json"
    )
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))

    for case in fixture["cases"]:
        assert _tokens(case["text"]) == set(case["tokens"]), case["text"]
