import json
from copy import deepcopy

import pytest

from app.eval.cluster_independent import ARTICLE_FIELDS
from app.eval.cluster_snapshot import build_snapshot, decode_chunks


@pytest.mark.parametrize("newline", ["\n", "\r\n"])
def test_decodes_unicode_pipes_and_boundary_whitespace_without_truncation(tmp_path, newline):
    row = {"topicId": 1, "sourceArticleId": 2,
           "body": "한글 😀 \u0085 \u2028 \u2029 |END 원문 끝 "}
    payload = json.dumps(row, ensure_ascii=False)
    boundary = payload.index(" 원문") + 1
    path = tmp_path / "chunks.txt"
    path.write_text(
        f"1:2|1|{payload[:boundary]}|END{newline}1:2|2|{payload[boundary:]}|END{newline}",
        encoding="utf-8",
    )
    assert decode_chunks(path) == [row]


@pytest.mark.parametrize("contents", [
    '1:2|1|{"topicId":1,"sourceArticleId":2}\n',
    '1:2|2|{"topicId":1,"sourceArticleId":2}|END\n',
    '1:2|1|{}|END\n1:2|1|{}|END\n',
    '1:2|1|{"topicId":1,"sourceArticleId":3}|END\n',
])
def test_rejects_corrupt_chunk_stream(tmp_path, contents):
    path = tmp_path / "chunks.txt"
    path.write_text(contents, encoding="utf-8")
    with pytest.raises(ValueError):
        decode_chunks(path)


def _rows():
    return [
        {
            "sourceArticleId": article_id, "sourceRunId": 11,
            "sourceRunIds": [11, 12 + article_id % 2], "topicId": topic_id,
            "title": f"원본 {article_id}", "summary": None, "body": "원문 유지",
            "fetchStatus": "FULLTEXT", "sourceId": 1, "publisher": "발행사",
            "reliabilityScore": 0.8, "publishedAt": "2026-09-04T09:00:00+09:00",
            "observedAt": "2026-09-04T10:00:00+09:00",
            "topicRequiredKeywords": ["반도체", "장비"],
            "topicOptionalKeywords": ["장비", "제조"], "topicQueryText": "반도체 신규 제조",
            "snapshotAt": "2026-09-04T11:00:00+09:00",
        }
        for topic_id in (3, 7) for article_id in range(10, 50)
    ]


def _build(rows):
    return build_snapshot(
        rows, per_topic=40, seed="synthetic", window_end="2026-09-04T11:00:00+09:00",
        lookback_hours=48, first_seen_since="2026-09-02T11:00:00+09:00",
        sql_chunks_sha256="a" * 64,
    )


def test_build_snapshot_maps_source_topic_identity_and_preserves_source_order():
    rows = _rows()
    original = deepcopy(rows)
    snapshot = _build(rows)
    articles = snapshot["articles"]
    assert len(articles) == len({article["articleId"] for article in articles}) == 80
    repeated = [article for article in articles if article["sourceArticleId"] == 10]
    assert {article["articleId"] for article in repeated} == {83, 87}
    assert [article["sourceArticleId"] for article in sorted(
        articles, key=lambda article: article["articleId"]
    )] == sorted(article["sourceArticleId"] for article in articles)
    assert all(set(article) == ARTICLE_FIELDS for article in articles)
    assert all(article["topicKeywords"] == ["반도체", "장비", "제조", "신규"]
               for article in articles)
    assert snapshot["sourceRuns"] == [11, 12, 13]
    assert snapshot["provenance"]["textSnapshotTimes"] == ["2026-09-04T11:00:00+09:00"]
    assert snapshot["provenance"]["candidateCounts"] == {"3": 40, "7": 40}
    assert snapshot["provenance"]["sqlChunksSha256"] == "a" * 64
    assert rows == original
    assert _build(list(reversed(rows))) == snapshot


@pytest.mark.parametrize("case", ["duplicate", "invalid_id", "overflow", "extra_field"])
def test_build_snapshot_rejects_ambiguous_ids_and_mapping_drift(case):
    rows = _rows()
    if case == "duplicate":
        rows.append(dict(rows[0]))
    elif case == "invalid_id":
        rows[0]["topicId"] = True
    elif case == "overflow":
        rows[0]["sourceArticleId"] = 2**63 - 1
    else:
        rows[0]["unexpected"] = "not part of the blind whitelist"
    with pytest.raises(ValueError):
        _build(rows)
