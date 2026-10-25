"""Seeds the ingredient catalogue from the packaged CSV built by scripts/build_catalogue.py.

Ingredient IDs are derived from the name, so loading twice updates the same rows and
recipes that reference catalogue IDs stay valid across databases.
"""

from __future__ import annotations

import csv
from importlib import resources
from pathlib import Path
from uuid import NAMESPACE_URL, UUID, uuid5

from mealiq.domain import Ingredient, NutritionSummary
from mealiq.persistence.repositories import IngredientRepository

_NAMESPACE = uuid5(NAMESPACE_URL, "https://github.com/Ayech14/MealIQ/ingredients")


def ingredient_id_for(name: str) -> UUID:
    return uuid5(_NAMESPACE, name.strip().lower())


def read_catalogue(path: Path | None = None) -> list[tuple[Ingredient, list[str]]]:
    """Parse the catalogue CSV into ingredients plus their substitute names."""
    source = path or resources.files("mealiq.data").joinpath("ingredients.csv")
    with source.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    return [(_ingredient(row), _split(row["substitutes"])) for row in rows]


def load_catalogue(repository: IngredientRepository, path: Path | None = None) -> int:
    """Insert or update every catalogue ingredient, then link substitutes. Returns the count."""
    entries = read_catalogue(path)
    by_name = {ingredient.name: ingredient for ingredient, _ in entries}
    for ingredient, _ in entries:
        repository.save(ingredient)
    for ingredient, substitute_names in entries:
        if substitute_names:
            repository.save(ingredient, substitutes=[by_name[n] for n in substitute_names])
    return len(entries)


def _ingredient(row: dict[str, str]) -> Ingredient:
    return Ingredient(
        ingredient_id=ingredient_id_for(row["name"]),
        name=row["name"],
        category=row["category"],
        allergens=set(_split(row["allergens"])),
        nutrition_per_100g=_nutrition(row),
        grams_per_unit=_float(row["grams_per_unit"]),
        grams_per_ml=_float(row["grams_per_ml"]),
        fdc_id=int(row["fdc_id"]) if row["fdc_id"] else None,
    )


def _nutrition(row: dict[str, str]) -> NutritionSummary | None:
    values = [_float(row[k]) for k in ("calories", "protein_g", "carbs_g", "fat_g")]
    if all(v is None for v in values):
        return None
    calories, protein, carbs, fat = (v or 0.0 for v in values)
    return NutritionSummary(calories, protein, carbs, fat, complete=all(v is not None for v in values))


def _float(value: str) -> float | None:
    return float(value) if value.strip() else None


def _split(value: str) -> list[str]:
    return [part.strip() for part in value.split(";") if part.strip()]
