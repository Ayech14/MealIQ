"""Quantity value object with unit conversion inside one dimension (mass, volume, count).

Converting between dimensions (for example cups to grams) needs ingredient data, so it
is not done here; NutritionService handles that with the ingredient catalogue (D09).
"""

from dataclasses import dataclass
from enum import Enum


class Dimension(str, Enum):
    MASS = "MASS"
    VOLUME = "VOLUME"
    COUNT = "COUNT"


# canonical unit -> (dimension, size in the dimension's base unit: g, ml or unit)
_UNITS: dict[str, tuple[Dimension, float]] = {
    "mg": (Dimension.MASS, 0.001),
    "g": (Dimension.MASS, 1.0),
    "kg": (Dimension.MASS, 1000.0),
    "oz": (Dimension.MASS, 28.349523125),
    "lb": (Dimension.MASS, 453.59237),
    "ml": (Dimension.VOLUME, 1.0),
    "l": (Dimension.VOLUME, 1000.0),
    "tsp": (Dimension.VOLUME, 4.92892159375),
    "tbsp": (Dimension.VOLUME, 14.78676478125),
    "cup": (Dimension.VOLUME, 236.5882365),
    "fl_oz": (Dimension.VOLUME, 29.5735295625),
    "unit": (Dimension.COUNT, 1.0),
    "dozen": (Dimension.COUNT, 12.0),
}

_ALIASES: dict[str, str] = {
    "milligram": "mg", "milligrams": "mg",
    "gram": "g", "grams": "g", "gr": "g",
    "kilogram": "kg", "kilograms": "kg", "kgs": "kg",
    "ounce": "oz", "ounces": "oz",
    "pound": "lb", "pounds": "lb", "lbs": "lb",
    "milliliter": "ml", "milliliters": "ml", "millilitre": "ml", "millilitres": "ml",
    "liter": "l", "liters": "l", "litre": "l", "litres": "l",
    "teaspoon": "tsp", "teaspoons": "tsp",
    "tablespoon": "tbsp", "tablespoons": "tbsp",
    "cups": "cup",
    "fl oz": "fl_oz", "floz": "fl_oz",
    "units": "unit", "piece": "unit", "pieces": "unit", "pc": "unit", "pcs": "unit",
    "each": "unit", "whole": "unit", "item": "unit", "items": "unit",
}


class UnitError(ValueError):
    """Raised for an unknown unit or a conversion across dimensions."""


def normalize_unit(unit: str) -> str:
    """Return the canonical spelling of a unit, or raise UnitError if it is unknown."""
    key = unit.strip().lower()
    key = _ALIASES.get(key, key)
    if key not in _UNITS:
        raise UnitError(f"Unknown unit: {unit!r}")
    return key


def is_known_unit(unit: str) -> bool:
    try:
        normalize_unit(unit)
    except UnitError:
        return False
    return True


@dataclass(frozen=True)
class Quantity:
    amount: float
    unit: str

    def __post_init__(self) -> None:
        if self.amount < 0:
            raise ValueError(f"Quantity cannot be negative: {self.amount}")
        object.__setattr__(self, "unit", normalize_unit(self.unit))

    @property
    def dimension(self) -> Dimension:
        return _UNITS[self.unit][0]

    def is_compatible(self, unit: str) -> bool:
        """True when this quantity can be converted to the given unit."""
        return self.amount == 0 or _UNITS[normalize_unit(unit)][0] == self.dimension

    def convert_to(self, unit: str) -> "Quantity":
        target = normalize_unit(unit)
        # Zero of anything is zero of anything else; this lets an empty pantry
        # quantity be compared with a requirement in any unit.
        if self.amount == 0:
            return Quantity(0.0, target)
        dimension, factor = _UNITS[target]
        if dimension != self.dimension:
            raise UnitError(f"Cannot convert {self.unit} ({self.dimension.value}) to {target} ({dimension.value})")
        base = self.amount * _UNITS[self.unit][1]
        return Quantity(base / factor, target)

    def add(self, other: "Quantity") -> "Quantity":
        """Sum, expressed in this quantity's unit."""
        return Quantity(self.amount + other.convert_to(self.unit).amount, self.unit)

    def subtract(self, other: "Quantity") -> "Quantity":
        """Remaining amount after taking `other` away, never below zero."""
        return Quantity(max(0.0, self.amount - other.convert_to(self.unit).amount), self.unit)

    def __str__(self) -> str:
        return f"{self.amount:g} {self.unit}"
