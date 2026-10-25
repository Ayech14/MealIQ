"""In-memory repository fakes for service tests (D08). Saved objects are deep-copied,
like a database round trip, so a test cannot pass by mutating shared objects."""

from copy import deepcopy

import pytest

from mealiq.domain import Pantry
from mealiq.events import PlanImpactObserver
from mealiq.persistence.catalogue import read_catalogue
from mealiq.persistence.repositories import IngredientRepository, PantryRepository, ProfileRepository


class InMemoryProfileRepository(ProfileRepository):
    def __init__(self):
        self.rows = {}

    def find_by_id(self, profile_id):
        return deepcopy(self.rows.get(profile_id))

    def save(self, profile):
        self.rows[profile.profile_id] = deepcopy(profile)


class InMemoryPantryRepository(PantryRepository):
    def __init__(self):
        self.rows = {}

    def find_by_profile_id(self, profile_id):
        return deepcopy(self.rows.get(profile_id)) or Pantry(profile_id=profile_id)

    def save(self, pantry):
        self.rows[pantry.profile_id] = deepcopy(pantry)


class CatalogueIngredientRepository(IngredientRepository):
    """Read-only view of the packaged USDA catalogue."""

    def __init__(self):
        entries = read_catalogue()
        self._by_name = {i.name: i for i, _ in entries}
        self._by_id = {i.ingredient_id: i for i, _ in entries}

    def find_by_id(self, ingredient_id):
        return self._by_id.get(ingredient_id)

    def find_by_name(self, name):
        return self._by_name.get(name.strip().lower())

    def find_substitutes(self, ingredient):
        return []

    def save(self, ingredient, substitutes=()):
        raise NotImplementedError


class RecordingObserver(PlanImpactObserver):
    def __init__(self):
        self.events = []

    def on_plan_relevant_change(self, event):
        self.events.append(event)


@pytest.fixture
def ingredients():
    return CatalogueIngredientRepository()


@pytest.fixture
def observer():
    return RecordingObserver()


@pytest.fixture
def profile_repo():
    return InMemoryProfileRepository()


@pytest.fixture
def pantry_repo():
    return InMemoryPantryRepository()
