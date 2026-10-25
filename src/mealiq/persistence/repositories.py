"""Repository interfaces from the Stage 1 persistence package.

Services depend on these abstractions only; mealiq.persistence.sql provides the
SQLAlchemy implementations, and tests can substitute in-memory fakes (D08).
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Iterable
from uuid import UUID

from mealiq.contracts import RecipeQuery
from mealiq.domain import GroceryList, Ingredient, MealPlan, Pantry, Recipe, UserProfile


class ProfileRepository(ABC):
    @abstractmethod
    def find_by_id(self, profile_id: UUID) -> UserProfile | None: ...

    @abstractmethod
    def save(self, profile: UserProfile) -> None: ...


class PantryRepository(ABC):
    @abstractmethod
    def find_by_profile_id(self, profile_id: UUID) -> Pantry:
        """Return the profile's pantry, or a new empty one if none is stored yet."""

    @abstractmethod
    def save(self, pantry: Pantry) -> None: ...


class MealPlanRepository(ABC):
    @abstractmethod
    def find_by_id(self, plan_id: UUID) -> MealPlan | None: ...

    @abstractmethod
    def find_active_by_profile_id(self, profile_id: UUID) -> MealPlan | None: ...

    @abstractmethod
    def save(self, plan: MealPlan) -> None: ...


class GroceryListRepository(ABC):
    @abstractmethod
    def find_by_plan_id(self, plan_id: UUID) -> GroceryList | None: ...

    @abstractmethod
    def save(self, grocery_list: GroceryList) -> None: ...


class RecipeRepository(ABC):
    @abstractmethod
    def find_by_id(self, recipe_id: UUID) -> Recipe | None: ...

    @abstractmethod
    def search(self, query: RecipeQuery) -> list[Recipe]:
        """Recipes matching meal type, time and ingredient filters.

        Cost filtering needs price data and is applied by the caller, not here.
        """

    @abstractmethod
    def save(self, recipe: Recipe) -> None: ...


class IngredientRepository(ABC):
    @abstractmethod
    def find_by_id(self, ingredient_id: UUID) -> Ingredient | None: ...

    @abstractmethod
    def find_by_name(self, name: str) -> Ingredient | None:
        """Case-insensitive exact match on the catalogue name."""

    @abstractmethod
    def find_substitutes(self, ingredient: Ingredient) -> list[Ingredient]: ...

    @abstractmethod
    def save(self, ingredient: Ingredient, substitutes: Iterable[Ingredient] = ()) -> None:
        """Insert or update a catalogue entry (used by the catalogue import)."""
