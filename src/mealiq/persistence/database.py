"""Engine, session factory and schema creation."""

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import sessionmaker

from mealiq.config import get_settings
from mealiq.persistence.sql.tables import Base


def make_engine(url: str | None = None) -> Engine:
    url = url or get_settings().database_url
    engine = create_engine(url)
    if engine.dialect.name == "sqlite":
        # SQLite ignores foreign keys unless asked; keep it as strict as Postgres.
        event.listen(engine, "connect", lambda conn, _: conn.execute("PRAGMA foreign_keys=ON"))
    return engine


def make_session_factory(engine: Engine) -> sessionmaker:
    return sessionmaker(bind=engine, expire_on_commit=False)


def create_schema(engine: Engine) -> None:
    """Create any missing tables. Existing tables are left untouched."""
    Base.metadata.create_all(engine)
