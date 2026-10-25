import os

import pytest

from mealiq.persistence.database import create_schema, make_engine, make_session_factory
from mealiq.persistence.sql.tables import Base

_BACKENDS = ["sqlite"]
if os.environ.get("MEALIQ_TEST_DATABASE_URL"):
    _BACKENDS.append("postgres")


@pytest.fixture(params=_BACKENDS)
def session_factory(request, tmp_path):
    """Fresh schema per test: a SQLite file always, and Postgres when
    MEALIQ_TEST_DATABASE_URL points at a disposable database."""
    if request.param == "sqlite":
        engine = make_engine(f"sqlite:///{tmp_path / 'test.db'}")
    else:
        engine = make_engine(os.environ["MEALIQ_TEST_DATABASE_URL"])
        Base.metadata.drop_all(engine)
    create_schema(engine)
    yield make_session_factory(engine)
    if request.param == "postgres":
        Base.metadata.drop_all(engine)
    engine.dispose()
