from app.core.safecast import safe_bool, safe_float, safe_int


def test_string_false_does_not_become_truthy() -> None:
    assert safe_bool("false") is False
    assert safe_bool("TRUE") is True
    assert safe_bool("unknown") is None


def test_numeric_casts_reject_booleans_and_non_finite_values() -> None:
    assert safe_int("42") == 42
    assert safe_int(True) is None
    assert safe_float("0.86") == 0.86
    assert safe_float("NaN") is None
    assert safe_float("Infinity") is None
