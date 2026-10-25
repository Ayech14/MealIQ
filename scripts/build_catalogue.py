"""Build the packaged ingredient catalogue from USDA FoodData Central SR Legacy (D09).

Usage:
    python scripts/build_catalogue.py <path-to-unzipped-SR-Legacy-CSV-folder>

Input:  scripts/catalogue_selection.csv - the curated ingredient list. For each
        ingredient it names the USDA fdc_id, the MealIQ category, allergens and
        substitutes (curated by hand, not USDA data), and regexes that pick which USDA
        portion defines "1 unit" and which defines a volume (cup/tbsp/tsp).
Output: src/mealiq/data/ingredients.csv - nutrition per 100 g and gram weights, taken
        from USDA. The output keeps fdc_id and the USDA portion text so every value can
        be traced back to its source.

The full USDA download (about 38 MB unpacked) is not committed; this script is rerun
only when the selection changes. Source dataset: FoodData_Central_sr_legacy_food_csv_2018-04.
"""

from __future__ import annotations

import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SELECTION = ROOT / "scripts" / "catalogue_selection.csv"
OUTPUT = ROOT / "src" / "mealiq" / "data" / "ingredients.csv"

ENERGY_IDS = ("1008", "2047", "2048")  # kcal; 1008 is the standard SR value
PROTEIN, FAT, CARBS = "1003", "1004", "1005"

ML_PER = {"cup": 236.5882365, "tbsp": 14.78676478125, "tablespoon": 14.78676478125,
          "tsp": 4.92892159375, "teaspoon": 4.92892159375, "fl oz": 29.5735295625}

FIELDS = [
    "name", "fdc_id", "usda_description", "category", "allergens",
    "calories", "protein_g", "carbs_g", "fat_g",
    "grams_per_unit", "unit_portion", "grams_per_ml", "volume_portion", "substitutes",
]


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def portion_text(row: dict[str, str]) -> str:
    return (row["modifier"] or row["portion_description"]).strip()


def pick_portion(portions: list[dict[str, str]], pattern: str) -> dict[str, str] | None:
    """First portion whose text matches one of the |-separated regexes, in pattern order."""
    for alternative in pattern.split("|"):
        for p in portions:
            if re.search(alternative, portion_text(p), re.I):
                return p
    return None


def ml_of(portion: dict[str, str]) -> float | None:
    text = portion_text(portion).lower()
    for unit, ml in ML_PER.items():
        if text.startswith(unit):
            return ml * float(portion["amount"])
    return None


def build(usda_dir: Path) -> list[dict[str, str]]:
    selection = read_csv(SELECTION)
    wanted = {row["fdc_id"] for row in selection}

    descriptions = {r["fdc_id"]: r["description"] for r in read_csv(usda_dir / "food.csv") if r["fdc_id"] in wanted}
    nutrients: dict[str, dict[str, float]] = defaultdict(dict)
    for r in read_csv(usda_dir / "food_nutrient.csv"):
        if r["fdc_id"] in wanted and r["nutrient_id"] in (*ENERGY_IDS, PROTEIN, FAT, CARBS):
            nutrients[r["fdc_id"]][r["nutrient_id"]] = float(r["amount"])
    portions: dict[str, list[dict[str, str]]] = defaultdict(list)
    for r in read_csv(usda_dir / "food_portion.csv"):
        if r["fdc_id"] in wanted:
            portions[r["fdc_id"]].append(r)

    out, problems = [], []
    for sel in selection:
        fdc = sel["fdc_id"]
        if fdc not in descriptions:
            problems.append(f"{sel['name']}: fdc_id {fdc} not found")
            continue
        n = nutrients[fdc]
        energy = next((n[i] for i in ENERGY_IDS if i in n), None)

        grams_per_unit = unit_text = ""
        if sel["unit_portion"]:
            p = pick_portion(portions[fdc], sel["unit_portion"])
            if p is None:
                problems.append(f"{sel['name']}: no unit portion matches {sel['unit_portion']!r}")
            else:
                grams_per_unit = f"{float(p['gram_weight']) / float(p['amount']):.4g}"
                unit_text = portion_text(p)

        grams_per_ml = volume_text = ""
        if sel["volume_portion"]:
            p = pick_portion(portions[fdc], sel["volume_portion"])
            ml = ml_of(p) if p else None
            if ml is None:
                problems.append(f"{sel['name']}: no volume portion matches {sel['volume_portion']!r}")
            else:
                grams_per_ml = f"{float(p['gram_weight']) / ml:.4g}"
                volume_text = portion_text(p)

        out.append({
            "name": sel["name"],
            "fdc_id": fdc,
            "usda_description": descriptions[fdc],
            "category": sel["category"],
            "allergens": sel["allergens"],
            "calories": _fmt(energy),
            "protein_g": _fmt(n.get(PROTEIN)),
            "carbs_g": _fmt(n.get(CARBS)),
            "fat_g": _fmt(n.get(FAT)),
            "grams_per_unit": grams_per_unit,
            "unit_portion": unit_text,
            "grams_per_ml": grams_per_ml,
            "volume_portion": volume_text,
            "substitutes": sel["substitutes"],
        })

    names = {row["name"] for row in out}
    for row in out:
        for sub in filter(None, row["substitutes"].split(";")):
            if sub not in names:
                problems.append(f"{row['name']}: substitute {sub!r} is not in the selection")
    if problems:
        raise SystemExit("Catalogue build failed:\n  " + "\n  ".join(problems))
    return out


def _fmt(value: float | None) -> str:
    return "" if value is None else f"{value:g}"


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    rows = build(Path(sys.argv[1]))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} ingredients to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
