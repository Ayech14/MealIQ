from sqlalchemy import func, inspect, select

from mealiq.persistence.bootstrap import init_database
from mealiq.persistence.catalogue import read_catalogue
from mealiq.persistence.database import make_engine, make_session_factory
from mealiq.persistence.sql import SqlIngredientRepository
from mealiq.persistence.sql.tables import Base, IngredientRow, IngredientSubstituteRow


def test_fresh_database_gets_all_tables_and_the_catalogue(tmp_path):
    engine = make_engine(f"sqlite:///{tmp_path / 'fresh.db'}")
    result = init_database(engine)

    assert set(result.tables_created) == set(Base.metadata.tables)
    assert result.ingredients_loaded == len(read_catalogue())
    egg = SqlIngredientRepository(make_session_factory(engine)).find_by_name("egg")
    assert egg is not None and egg.grams_per_unit == 50
    engine.dispose()


def test_running_twice_creates_nothing_new_and_duplicates_nothing(tmp_path):
    engine = make_engine(f"sqlite:///{tmp_path / 'twice.db'}")
    init_database(engine)
    session_factory = make_session_factory(engine)
    with session_factory() as session:
        ingredients = session.scalar(select(func.count()).select_from(IngredientRow))
        links = session.scalar(select(func.count()).select_from(IngredientSubstituteRow))

    second = init_database(engine)

    assert second.tables_created == []
    with session_factory() as session:
        assert session.scalar(select(func.count()).select_from(IngredientRow)) == ingredients
        assert session.scalar(select(func.count()).select_from(IngredientSubstituteRow)) == links
    assert set(inspect(engine).get_table_names()) == set(Base.metadata.tables)
    engine.dispose()
