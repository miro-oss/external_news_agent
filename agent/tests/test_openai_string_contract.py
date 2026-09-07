import json

import pytest
from jsonschema import ValidationError
from test_openai_contract import validator, wire_analysis

from app.llm.openai_contract import output_contract
from app.schemas.analyze import AnalyzeOutput


@pytest.mark.parametrize("speaker", ["", " ", "\t\n", "가" * 201])
def test_strict_wire_rejects_empty_blank_or_overlong_attribution(speaker):
    wire = wire_analysis()
    wire["sections"][0]["bullets"][0].update(claimType="OPINION", attributedTo=speaker)
    with pytest.raises(ValidationError):
        validator().validate(wire)


@pytest.mark.parametrize("text", ["", " \n ", "가" * 81])
def test_strict_wire_enforces_bullet_length_after_sdk_transformation(text):
    wire = wire_analysis()
    wire["sections"][0]["bullets"][0]["text"] = text
    with pytest.raises(ValidationError):
        validator().validate(wire)


@pytest.mark.parametrize("speaker", ["김", "김 대표", "가" * 200, "김\n대표"])
def test_valid_korean_and_multiline_attribution_round_trips(speaker):
    wire = wire_analysis()
    wire["sections"][0]["bullets"][0].update(claimType="OPINION", attributedTo=speaker)
    validator().validate(wire)
    public = output_contract(AnalyzeOutput.model_json_schema(by_alias=True)).public_text(
        json.dumps(wire)
    )
    assert AnalyzeOutput.model_validate_json(public).sections[0].bullets[0].attributed_to == speaker
