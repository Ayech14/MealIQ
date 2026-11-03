"""Build the packaged grocery price table from Statistics Canada table 18-10-0245-01.

Usage:
    python scripts/build_prices.py <path-to-18100245.csv>

Input:  scripts/price_selection.csv - which StatCan product prices each catalogue
        ingredient (curated by hand), with a note where the product is only a close
        match (for example frozen instead of fresh). Ingredients whose StatCan "unit"
        means something else than the catalogue's unit (a head of lettuce against a leaf)
        are left out, so their prices stay unknown instead of wrong.
Output: src/main/resources/catalogue/prices.csv - the latest Canada-wide average price
        of each product, its package size, the StatCan product name and the reference
        month, so every price can be traced back to its source.

The StatCan download (Monthly average retail prices for selected products, about 19 MB
unpacked) is not committed: https://www150.statcan.gc.ca/n1/tbl/csv/18100245-eng.zip
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SELECTION = ROOT / "scripts" / "price_selection.csv"
OUTPUT = ROOT / "src" / "main" / "resources" / "catalogue" / "prices.csv"

UNITS = {"gram": "g", "grams": "g", "kilogram": "kg", "kilograms": "kg",
         "litre": "l", "litres": "l", "millilitres": "ml"}


def package(product: str) -> tuple[float, str]:
    """Package size from the end of a StatCan product name, e.g. "Tofu, 350 grams"."""
    size = product.rsplit(",", 1)[1].strip()
    if size == "per kilogram":
        return 1, "kg"
    if size == "unit":
        return 1, "unit"
    if size == "1 dozen":
        return 12, "unit"
    amount, unit = size.split()
    return float(amount), UNITS[unit]


def main(source: Path) -> None:
    latest: dict[str, dict[str, str]] = {}
    with source.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            product = row["Products"].strip()
            if row["GEO"] != "Canada" or not row["VALUE"]:
                continue
            if product not in latest or row["REF_DATE"] > latest[product]["REF_DATE"]:
                latest[product] = row

    with SELECTION.open(newline="") as f:
        selection = list(csv.DictReader(f))
    with OUTPUT.open("w", newline="") as f:
        out = csv.writer(f, lineterminator="\n")
        out.writerow(["ingredient", "price", "package_amount", "package_unit", "statcan_product",
                      "reference_month", "note"])
        for pick in selection:
            row = latest[pick["statcan_product"]]
            amount, unit = package(pick["statcan_product"])
            out.writerow([pick["ingredient"], row["VALUE"], f"{amount:g}", unit, pick["statcan_product"],
                          row["REF_DATE"], pick["note"]])
    print(f"Wrote {len(selection)} prices to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(Path(sys.argv[1]))
