import json

import pytest

from app.eval.cluster_snapshot import decode_chunks


def test_decodes_unicode_pipes_and_boundary_whitespace_without_truncation(tmp_path):
    row = {"topicId": 1, "sourceArticleId": 2, "body": "한글 😀 |END 원문 끝 "}
    payload = json.dumps(row, ensure_ascii=False)
    boundary = payload.index(" 원문") + 1
    path = tmp_path / "chunks.txt"
    path.write_text(
        f"1:2|1|{payload[:boundary]}|END\n1:2|2|{payload[boundary:]}|END\n",
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
