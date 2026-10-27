"""Prepare a database for MealIQ: create missing tables and load the ingredient catalogue.

Usage (uses DATABASE_URL from .env):
    python -m mealiq.persistence.bootstrap

Safe to run more than once: existing tables are kept and catalogue rows are updated in
place, because ingredient IDs are derived from their names.
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import Engine, inspect

from mealiq.persistence.catalogue import load_catalogue
from mealiq.persistence.database import create_schema, make_engine, make_session_factory
from mealiq.persistence.sql import SqlIngredientRepository


@dataclass(frozen=True)
class BootstrapResult:
    tables_created: list[str]
    ingredients_loaded: int


def init_database(engine: Engine) -> BootstrapResult:
    before = set(inspect(engine).get_table_names())
    create_schema(engine)
    created = sorted(set(inspect(engine).get_table_names()) - before)
    loaded = load_catalogue(SqlIngredientRepository(make_session_factory(engine)))
    return BootstrapResult(tables_created=created, ingredients_loaded=loaded)


def main() -> None:
    engine = make_engine()
    try:
        result = init_database(engine)
    finally:
        engine.dispose()
    created = ", ".join(result.tables_created) or "none (all tables already existed)"
    print(f"Tables created: {created}")
    print(f"Ingredients loaded or updated: {result.ingredients_loaded}")


if __name__ == "__main__":
    main()
