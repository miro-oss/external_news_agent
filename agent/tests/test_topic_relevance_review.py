import copy
import json
from html.parser import HTMLParser

import pytest

from app.eval import topic_relevance_review as review_tool
from app.eval.topic_relevance_review import (
    build_packet,
    canonical_json,
    main,
    read_json,
    render_html,
    sha256,
    validate_packet,
    validate_review,
)


def _source():
    return {
        "datasetId": "topic-relevance.synthetic.v1",
        "createdAt": "2026-09-17T02:00:00Z",
        "cases": [
            {
                "caseId": "case-001",
                "topic": {
                    "id": 11,
                    "name": "반도체 제조 장비",
                    "queryText": "반도체 장비 공정",
                    "requiredKeywords": ["공정"],
                    "optionalKeywords": ["라인", "반도체 장비"],
                    "excludedKeywords": [],
                },
                "article": {
                    "id": 101,
                    "title": "공정위, 온라인 결제 서비스 조사",
                    "summary": "결제 사업자의 시장 경쟁에 관한 조사",
                    "bodyText": "공정위가 온라인 결제 사업자의 시장 경쟁 상황을 조사한다.",
                    "publisher": "검증용 매체",
                    "url": "https://example.com/article/101?a=1&b=2",
                    "publishedAt": "2026-09-17T09:00:00",
                    "bodyTruncated": False,
                },
            },
            {
                "caseId": "case-002",
                "topic": {
                    "id": 12,
                    "name": "금융 규제",
                    "queryText": None,
                    "requiredKeywords": [],
                    "optionalKeywords": ["결제"],
                    "excludedKeywords": ["게임"],
                },
                "article": {
                    "id": 101,
                    "title": "공정위, 온라인 결제 서비스 조사",
                    "summary": None,
                    "bodyText": "",
                    "publisher": "",
                    "url": None,
                    "publishedAt": None,
                    "bodyTruncated": True,
                },
            },
        ],
    }


def _review(packet, *, complete=False):
    return {
        "schemaVersion": 1,
        "purpose": "topic-relevance-human-labels",
        "datasetId": packet["datasetId"],
        "datasetSha256": packet["datasetSha256"],
        "exportKind": "final" if complete else "draft",
        "exportedAt": "2026-09-17T03:00:00.000Z",
        "records": [
            {
                "caseId": case["caseId"],
                "inputSha256": case["inputSha256"],
                "decision": "UNCERTAIN" if complete else None,
                "reason": "",
                "updatedAt": "2026-09-17T02:59:00+09:00" if complete else None,
                "sourceOpened": False,
            }
            for case in packet["cases"]
        ],
    }


def _mutate(value, path, replacement):
    parts = path.split(".")
    current = value
    for part in parts[:-1]:
        current = current[int(part)] if isinstance(current, list) else current[part]
    if isinstance(current, list):
        current[int(parts[-1])] = replacement
    else:
        current[parts[-1]] = replacement


def _rehash(packet):
    for case in packet["cases"]:
        case["inputSha256"] = sha256({"topic": case["topic"], "article": case["article"]})
    packet["datasetSha256"] = sha256(
        {key: value for key, value in packet.items() if key != "datasetSha256"}
    )


def test_build_preserves_all_blind_inputs_and_binds_displayed_metadata():
    source = _source()
    original = copy.deepcopy(source)
    packet = build_packet(source)
    assert source == original
    assert validate_packet(packet) is packet
    for case, supplied in zip(packet["cases"], source["cases"], strict=True):
        assert {key: value for key, value in case.items() if key != "inputSha256"} == supplied
        assert case["inputSha256"] == sha256({"topic": case["topic"], "article": case["article"]})
    source["cases"][0]["topic"]["name"] = "수정된 원본"
    assert packet["cases"][0]["topic"]["name"] == "반도체 제조 장비"


def test_canonical_hash_ignores_key_order_but_preserves_unicode_and_array_order():
    assert canonical_json({"z": "공정", "a": 1}) == '{"a":1,"z":"공정"}'
    assert sha256({"a": 1, "z": "공정"}) == sha256({"z": "공정", "a": 1})
    assert sha256(["공정", "장비"]) != sha256(["장비", "공정"])
    with pytest.raises(ValueError):
        canonical_json(float("nan"))


def test_source_preserves_java_timestamp_nanoseconds_without_rounding():
    source = _source()
    stamp = "2026-09-17T09:00:00.123456789"
    source["cases"][0]["article"]["publishedAt"] = stamp
    assert build_packet(source)["cases"][0]["article"]["publishedAt"] == stamp


@pytest.mark.parametrize("path,value", [
    ("cases.0.prediction", "IRRELEVANT"),
    ("cases.0.topic.expected", "RELEVANT"),
    ("cases.0.article.samplingStratum", "hard-negative"),
    ("samplingStrata", ["negative"]),
    ("cases.0.topic.id", True),
    ("cases.0.article.id", 2**53),
    ("cases.0.article.id", 0),
    ("cases.0.article.id", 1.0),
    ("cases.0.article.title", "x" * 1001),
    ("cases.0.article.summary", "x" * 1001),
    ("cases.0.article.bodyText", "x" * 5001),
    ("cases.0.article.bodyTruncated", 1),
    ("cases.0.article.publishedAt", "2026-02-30T12:00:00Z"),
    ("cases.0.article.publishedAt", "2026-09-17"),
    ("cases.0.article.url", "javascript:alert(1)"),
    ("cases.0.article.url", "https://user:password@example.com/"),
    ("cases.0.article.url", "https://example.com/\nsecret"),
    ("cases.0.topic.requiredKeywords", [""]),
    ("cases.0.topic.requiredKeywords", "공정"),
    ("cases.0.topic.requiredKeywords", ["공정"] * 101),
    ("cases.0.topic.queryText", "공정" * 251),
    ("cases.0.topic.name", ""),
    ("cases.0.article.bodyText", "\ud800"),
    ("createdAt", "2026-09-17T03:00:00"),
    ("createdAt", "2026-09-17T03:00:00+25:00"),
    ("datasetId", "../escape"),
    ("cases", []),
])
def test_source_rejects_invalid_or_nonblind_inputs(path, value):
    source = _source()
    _mutate(source, path, value)
    with pytest.raises(ValueError):
        build_packet(source)


@pytest.mark.parametrize("duplicate", ["case", "pair"])
def test_duplicate_cases_and_article_topic_pairs_are_rejected(duplicate):
    source = _source()
    if duplicate == "case":
        source["cases"][1]["caseId"] = source["cases"][0]["caseId"]
    else:
        source["cases"][1]["topic"]["id"] = source["cases"][0]["topic"]["id"]
    with pytest.raises(ValueError, match="unique"):
        build_packet(source)


@pytest.mark.parametrize("path,value,rehash", [
    ("cases.0.article.title", "Edited title", False),
    ("cases.0.topic.optionalKeywords", ["Edited keyword"], False),
    ("cases.0.inputSha256", "a" * 64, False),
    ("datasetSha256", "a" * 64, False),
    ("createdAt", "2026-09-17T03:00:00Z", False),
    ("schemaVersion", True, True),
    ("schemaVersion", 1.0, True),
    ("purpose", "predictions", True),
    ("cases.0.article.prediction", "RELEVANT", True),
])
def test_packet_validates_schema_and_hashes(path, value, rehash):
    packet = build_packet(_source())
    _mutate(packet, path, value)
    if rehash:
        _rehash(packet)
    with pytest.raises(ValueError):
        validate_packet(packet)


def test_draft_summary_counts_blank_notes_and_external_source_opens():
    packet = build_packet(_source())
    review = _review(packet)
    review["records"][0].update(
        decision="IRRELEVANT", updatedAt="2026-09-17T02:05:00Z", sourceOpened=True
    )
    review["records"][1].update(reason="나중에 다시 보기", updatedAt="2026-09-17T02:06:00Z")
    summary = validate_review(packet, review)
    assert summary["decisions"] == {"RELEVANT": 0, "IRRELEVANT": 1, "UNCERTAIN": 0}
    assert summary["blank"] == 1
    assert summary["externalSourceOpened"] == 1
    assert summary["complete"] is False
    with pytest.raises(ValueError, match="every case"):
        validate_review(packet, review, require_complete=True)


def test_complete_labels_allow_blank_reasons_and_any_record_order():
    packet = build_packet(_source())
    review = _review(packet, complete=True)
    review["records"].reverse()
    review["records"][0]["decision"] = "RELEVANT"
    summary = validate_review(packet, review, require_complete=True)
    assert summary["complete"] is True
    assert summary["decisions"]["RELEVANT"] == 1
    assert summary["decisions"]["UNCERTAIN"] == 1


@pytest.mark.parametrize("path,value", [
    ("datasetId", "another-dataset"),
    ("datasetSha256", "0" * 64),
    ("records.0.inputSha256", "0" * 64),
    ("records.0.caseId", "unknown-case"),
    ("records.0.decision", "APPROVED"),
    ("records.0.decision", ["RELEVANT"]),
    ("records.0.decision", True),
    ("records.0.updatedAt", None),
    ("records.0.updatedAt", "2026-02-30T03:00:00Z"),
    ("records.0.updatedAt", "2026-09-17T03:00:00"),
    ("records.0.reason", "x" * 2001),
    ("records.0.reason", None),
    ("records.0.sourceOpened", "false"),
    ("records.0.sourceOpened", 1),
    ("records.0.prediction", "RELEVANT"),
    ("records.0.decision", None),
    ("records.0.caseId", "case-002"),
    ("records", []),
    ("schemaVersion", True),
    ("schemaVersion", 1.0),
    ("purpose", "topic-relevance-blind-review"),
    ("exportKind", "complete"),
    ("exportedAt", "yesterday"),
    ("accuracy", 1.0),
])
def test_review_rejects_forged_mismatched_incomplete_or_invalid_exports(path, value):
    packet = build_packet(_source())
    review = _review(packet, complete=True)
    _mutate(review, path, value)
    with pytest.raises(ValueError):
        validate_review(packet, review)


def test_export_from_different_displayed_inputs_is_rejected_even_with_same_case_ids():
    packet = build_packet(_source())
    source2 = _source()
    source2["cases"][0]["article"]["bodyText"] += " 추가된 본문"
    other_packet = build_packet(source2)
    review = _review(other_packet, complete=True)
    review["datasetSha256"] = packet["datasetSha256"]
    with pytest.raises(ValueError, match="inputSha256"):
        validate_review(packet, review)


def test_html_embedding_cannot_close_script_and_preserves_original_hashed_inputs():
    source = _source()
    malicious = '</script><script>alert("x")</script>&\u2028\u2029'
    source["cases"][0]["article"]["title"] = malicious
    packet = build_packet(source)
    template = '<script type="application/json" id="packet">__REVIEW_PACKET_JSON__</script>'
    html = render_html(packet, template)
    assert html.count("</script>") == 1
    embedded = html.removeprefix('<script type="application/json" id="packet">').removesuffix(
        "</script>"
    )
    for literal in ("<", ">", "&", "\u2028", "\u2029"):
        assert literal not in embedded
    decoded = json.loads(embedded)
    assert decoded == packet
    assert decoded["cases"][0]["article"]["title"] == malicious
    validate_packet(decoded)


def test_real_template_embeds_packet_without_creating_article_scripts():
    class PacketParser(HTMLParser):
        def __init__(self):
            super().__init__()
            self.inside_packet = False
            self.packet_text = ""
            self.script_count = 0

        def handle_starttag(self, tag, attrs):
            if tag == "script":
                self.script_count += 1
                self.inside_packet = dict(attrs).get("id") == "reviewPacket"

        def handle_endtag(self, tag):
            if tag == "script":
                self.inside_packet = False

        def handle_data(self, data):
            if self.inside_packet:
                self.packet_text += data

    source = _source()
    source["cases"][0]["article"]["bodyText"] = '</script><script>alert("unexpected")</script>'
    packet = build_packet(source)
    rendered = render_html(packet)
    parser = PacketParser()
    parser.feed(rendered)
    assert parser.script_count == 2
    assert json.loads(parser.packet_text) == packet
    validate_packet(json.loads(parser.packet_text))


@pytest.mark.parametrize("template", ["<html></html>", "__REVIEW_PACKET_JSON__" * 2])
def test_template_requires_exactly_one_packet_insertion(template):
    with pytest.raises(ValueError, match="exactly one"):
        render_html(build_packet(_source()), template)


@pytest.mark.parametrize("raw", [
    '{"key": 1, "key": 2}',
    '{"nested": {"key": 1, "key": 2}}',
    '{"key": NaN}',
    '{"key": Infinity}',
])
def test_json_reader_rejects_duplicate_keys_and_nonfinite_numbers(tmp_path, raw):
    path = tmp_path / "invalid.json"
    path.write_text(raw)
    with pytest.raises(ValueError):
        read_json(path)


def test_json_reader_bounds_bytes_before_parsing(tmp_path, monkeypatch):
    monkeypatch.setattr(review_tool, "MAX_JSON_BYTES", 10)
    path = tmp_path / "large.json"
    path.write_bytes(b" " * 11)
    with pytest.raises(ValueError, match="size limit"):
        read_json(path)


def test_build_cli_creates_new_directory_and_refuses_overwrite(tmp_path, monkeypatch, capsys):
    source = tmp_path / "source.json"
    source.write_text(json.dumps(_source(), ensure_ascii=False), encoding="utf-8")
    output = tmp_path / "review"
    real_render_html = render_html
    monkeypatch.setattr(review_tool, "render_html", lambda packet: real_render_html(
        packet, '<script type="application/json">__REVIEW_PACKET_JSON__</script>'
    ))
    assert main(["build", "--source", str(source), "--output-dir", str(output)]) == 0
    packet = read_json(output / "packet.json")
    validate_packet(packet)
    assert (output / "review.html").is_file()
    assert json.loads(capsys.readouterr().out)["cases"] == 2
    with pytest.raises(SystemExit) as error:
        main(["build", "--source", str(source), "--output-dir", str(output)])
    assert error.value.code == 2
    assert read_json(output / "packet.json") == packet


def test_validate_cli_requires_original_packet_and_can_require_completion(tmp_path, capsys):
    packet = build_packet(_source())
    review = _review(packet)
    packet_path, review_path = tmp_path / "packet.json", tmp_path / "review.json"
    packet_path.write_text(json.dumps(packet, ensure_ascii=False), encoding="utf-8")
    review_path.write_text(json.dumps(review, ensure_ascii=False), encoding="utf-8")
    command = ["validate", "--packet", str(packet_path), "--review", str(review_path)]
    assert main(command) == 0
    assert json.loads(capsys.readouterr().out)["blank"] == 2
    with pytest.raises(SystemExit) as error:
        main([*command, "--require-complete"])
    assert error.value.code == 2
    review["records"][0]["decision"] = "INVALID"
    review_path.write_text(json.dumps(review, ensure_ascii=False), encoding="utf-8")
    with pytest.raises(SystemExit) as error:
        main(command)
    assert error.value.code == 2
