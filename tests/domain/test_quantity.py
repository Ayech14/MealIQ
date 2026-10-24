import pytest

from mealiq.domain.quantity import Dimension, Quantity, UnitError, is_known_unit


def test_aliases_are_normalized():
    assert Quantity(2, "Grams").unit == "g"
    assert Quantity(1, "tablespoons").unit == "tbsp"
    assert Quantity(3, "pieces").unit == "unit"


def test_unknown_unit_is_rejected():
    with pytest.raises(UnitError):
        Quantity(1, "bunch")
    assert is_known_unit("bunch") is False


def test_negative_amount_is_rejected():
    with pytest.raises(ValueError):
        Quantity(-1, "g")


@pytest.mark.parametrize(
    ("amount", "unit", "target", "expected"),
    [
        (1.5, "kg", "g", 1500),
        (1, "lb", "g", 453.59237),
        (1, "cup", "ml", 236.5882365),
        (3, "tsp", "tbsp", 1),
        (2, "dozen", "unit", 24),
    ],
)
def test_conversion_within_dimension(amount, unit, target, expected):
    assert Quantity(amount, unit).convert_to(target).amount == pytest.approx(expected)


def test_conversion_across_dimensions_fails():
    with pytest.raises(UnitError):
        Quantity(1, "cup").convert_to("g")


def test_zero_converts_to_any_unit():
    assert Quantity(0, "g").convert_to("unit") == Quantity(0, "unit")


def test_add_keeps_left_unit():
    total = Quantity(500, "g").add(Quantity(1, "kg"))
    assert total == Quantity(1500, "g")


def test_subtract_never_goes_below_zero():
    assert Quantity(200, "g").subtract(Quantity(150, "g")) == Quantity(50, "g")
    assert Quantity(200, "g").subtract(Quantity(1, "kg")) == Quantity(0, "g")


def test_dimension_and_compatibility():
    q = Quantity(2, "l")
    assert q.dimension is Dimension.VOLUME
    assert q.is_compatible("cup")
    assert not q.is_compatible("g")
