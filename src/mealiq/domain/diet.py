"""Hard dietary-restriction rules used by UserProfile.is_allowed().

A restriction excludes ingredients by catalogue category and/or by allergen tag. Stage 1
names restrictions as free-form strings; this table makes the supported ones explicit
so they can be enforced deterministically (D04).
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class DietRule:
    excluded_categories: frozenset[str] = frozenset()
    excluded_allergens: frozenset[str] = frozenset()


_MEAT = frozenset({"meat", "poultry"})
_SEAFOOD = frozenset({"fish", "shellfish"})

DIET_RULES: dict[str, DietRule] = {
    "vegetarian": DietRule(excluded_categories=_MEAT | _SEAFOOD),
    "pescatarian": DietRule(excluded_categories=_MEAT),
    "vegan": DietRule(
        excluded_categories=_MEAT | _SEAFOOD | {"dairy", "egg", "bee-product"},
        excluded_allergens=frozenset({"milk", "egg", "fish", "shellfish"}),
    ),
    "gluten-free": DietRule(excluded_allergens=frozenset({"gluten"})),
    "dairy-free": DietRule(excluded_categories=frozenset({"dairy"}), excluded_allergens=frozenset({"milk"})),
    "nut-free": DietRule(excluded_allergens=frozenset({"tree-nut", "peanut"})),
}


def normalize_tag(value: str) -> str:
    return value.strip().lower().replace("_", "-").replace(" ", "-")


def is_supported_restriction(name: str) -> bool:
    return normalize_tag(name) in DIET_RULES
