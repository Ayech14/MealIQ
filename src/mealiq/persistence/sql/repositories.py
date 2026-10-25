"""SQLAlchemy implementations of the repository interfaces.

Each method runs in its own short transaction. When a save replaces a child collection
(pantry items, plan meals, grocery items), the old children are deleted and flushed
before the new ones are added, so rows with the same key do not collide.
"""

from __future__ import annotations

from collections.abc import Iterable
from uuid import UUID

from sqlalchemy import delete, select
from sqlalchemy.orm import Session, sessionmaker

from mealiq.contracts import RecipeQuery
from mealiq.domain import GroceryList, Ingredient, MealPlan, Pantry, PlanStatus, Recipe, RecipeSource, UserProfile
from mealiq.persistence.repositories import (
    GroceryListRepository,
    IngredientRepository,
    MealPlanRepository,
    PantryRepository,
    ProfileRepository,
    RecipeRepository,
)
from mealiq.persistence.sql import mappers
from mealiq.persistence.sql.mappers import RowMapper
from mealiq.persistence.sql.tables import (
    GroceryListRow,
    IngredientRow,
    IngredientSubstituteRow,
    MealPlanRow,
    PantryRow,
    ProfileRow,
    RecipeRow,
)


class _SqlRepository:
    def __init__(self, session_factory: sessionmaker[Session]) -> None:
        self._session_factory = session_factory


def _replace_children(session: Session, collection: list, new_rows: list) -> None:
    collection.clear()
    session.flush()
    collection.extend(new_rows)


class SqlIngredientRepository(_SqlRepository, IngredientRepository):
    def find_by_id(self, ingredient_id: UUID) -> Ingredient | None:
        with self._session_factory() as session:
            row = session.get(IngredientRow, ingredient_id)
            return RowMapper().ingredient(row) if row else None

    def find_by_name(self, name: str) -> Ingredient | None:
        with self._session_factory() as session:
            row = session.scalar(select(IngredientRow).where(IngredientRow.name == name.strip().lower()))
            return RowMapper().ingredient(row) if row else None

    def find_substitutes(self, ingredient: Ingredient) -> list[Ingredient]:
        with self._session_factory() as session:
            rows = session.scalars(
                select(IngredientRow)
                .join(IngredientSubstituteRow, IngredientSubstituteRow.substitute_id == IngredientRow.id)
                .where(IngredientSubstituteRow.ingredient_id == ingredient.ingredient_id)
                .order_by(IngredientRow.name)
            )
            mapper = RowMapper()
            return [mapper.ingredient(r) for r in rows]

    def save(self, ingredient: Ingredient, substitutes: Iterable[Ingredient] = ()) -> None:
        with self._session_factory.begin() as session:
            row = session.get(IngredientRow, ingredient.ingredient_id) or IngredientRow(id=ingredient.ingredient_id)
            mappers.fill_ingredient_row(row, ingredient)
            session.add(row)
            substitutes = list(substitutes)
            if substitutes:
                session.flush()
                session.execute(
                    delete(IngredientSubstituteRow).where(
                        IngredientSubstituteRow.ingredient_id == ingredient.ingredient_id
                    )
                )
                session.add_all(
                    IngredientSubstituteRow(ingredient_id=ingredient.ingredient_id, substitute_id=s.ingredient_id)
                    for s in substitutes
                )


class SqlRecipeRepository(_SqlRepository, RecipeRepository):
    def find_by_id(self, recipe_id: UUID) -> Recipe | None:
        with self._session_factory() as session:
            row = session.get(RecipeRow, recipe_id)
            return RowMapper().recipe(row) if row else None

    def search(self, query: RecipeQuery) -> list[Recipe]:
        """Filters: meal type, total minutes, source, and ingredient names.

        An ingredient term matches any catalogue name containing it ("chicken" matches
        "chicken breast"). A recipe matches `include_ingredients` if it uses at least one
        of them; ranking by how many it uses is left to the planning strategies.
        """
        stmt = select(RecipeRow).order_by(RecipeRow.name)
        if query.max_minutes is not None:
            stmt = stmt.where(RecipeRow.prep_minutes + RecipeRow.cook_minutes <= query.max_minutes)
        if not query.allow_generated:
            stmt = stmt.where(RecipeRow.source != RecipeSource.GENERATED.value)
        include = [t.strip().lower() for t in query.include_ingredients if t.strip()]
        exclude = [t.strip().lower() for t in query.exclude_ingredients if t.strip()]

        with self._session_factory() as session:
            mapper = RowMapper()
            results = []
            for row in session.scalars(stmt):
                if query.meal_type is not None and query.meal_type.value not in row.meal_types:
                    continue
                names = [ri.ingredient.name for ri in row.ingredients]
                if include and not any(term in name for term in include for name in names):
                    continue
                if any(term in name for term in exclude for name in names):
                    continue
                results.append(mapper.recipe(row))
            return results

    def save(self, recipe: Recipe) -> None:
        with self._session_factory.begin() as session:
            missing = [
                req.ingredient.name
                for req in recipe.ingredients
                if session.get(IngredientRow, req.ingredient.ingredient_id) is None
            ]
            if missing:
                # Every recipe ingredient must come from the catalogue (D09).
                raise ValueError(f"Ingredients not in catalogue: {', '.join(missing)}")
            row = session.get(RecipeRow, recipe.recipe_id) or RecipeRow(id=recipe.recipe_id)
            mappers.fill_recipe_row(row, recipe)
            session.add(row)
            _replace_children(session, row.ingredients, mappers.recipe_ingredient_rows(recipe))


class SqlProfileRepository(_SqlRepository, ProfileRepository):
    def find_by_id(self, profile_id: UUID) -> UserProfile | None:
        with self._session_factory() as session:
            row = session.get(ProfileRow, profile_id)
            return RowMapper().profile(row) if row else None

    def save(self, profile: UserProfile) -> None:
        with self._session_factory.begin() as session:
            row = session.get(ProfileRow, profile.profile_id) or ProfileRow(id=profile.profile_id)
            mappers.fill_profile_row(row, profile)
            session.add(row)


class SqlPantryRepository(_SqlRepository, PantryRepository):
    def find_by_profile_id(self, profile_id: UUID) -> Pantry:
        with self._session_factory() as session:
            row = session.scalar(select(PantryRow).where(PantryRow.profile_id == profile_id))
            return RowMapper().pantry(row) if row else Pantry(profile_id=profile_id)

    def save(self, pantry: Pantry) -> None:
        with self._session_factory.begin() as session:
            row = session.scalar(select(PantryRow).where(PantryRow.profile_id == pantry.profile_id))
            if row is None:
                row = PantryRow(id=pantry.pantry_id, profile_id=pantry.profile_id)
                session.add(row)
            _replace_children(session, row.items, mappers.pantry_item_rows(pantry))


class SqlMealPlanRepository(_SqlRepository, MealPlanRepository):
    def find_by_id(self, plan_id: UUID) -> MealPlan | None:
        with self._session_factory() as session:
            row = session.get(MealPlanRow, plan_id)
            return RowMapper().meal_plan(row) if row else None

    def find_active_by_profile_id(self, profile_id: UUID) -> MealPlan | None:
        with self._session_factory() as session:
            row = session.scalar(
                select(MealPlanRow)
                .where(MealPlanRow.profile_id == profile_id, MealPlanRow.status == PlanStatus.ACTIVE.value)
                .order_by(MealPlanRow.start_date.desc())
                .limit(1)
            )
            return RowMapper().meal_plan(row) if row else None

    def save(self, plan: MealPlan) -> None:
        with self._session_factory.begin() as session:
            row = session.get(MealPlanRow, plan.plan_id)
            if row is None:
                row = MealPlanRow(id=plan.plan_id)
                session.add(row)
            row.profile_id = plan.profile_id
            row.start_date = plan.start_date
            row.days = plan.days
            row.status = plan.status.value
            _replace_children(session, row.meals, mappers.planned_meal_rows(plan))


class SqlGroceryListRepository(_SqlRepository, GroceryListRepository):
    def find_by_plan_id(self, plan_id: UUID) -> GroceryList | None:
        with self._session_factory() as session:
            row = session.scalar(select(GroceryListRow).where(GroceryListRow.plan_id == plan_id))
            return RowMapper().grocery_list(row) if row else None

    def save(self, grocery_list: GroceryList) -> None:
        with self._session_factory.begin() as session:
            row = session.scalar(select(GroceryListRow).where(GroceryListRow.plan_id == grocery_list.plan_id))
            if row is None:
                row = GroceryListRow(id=grocery_list.list_id, plan_id=grocery_list.plan_id)
                session.add(row)
            row.estimated_cost = grocery_list.estimated_cost
            row.stale = grocery_list.stale
            _replace_children(session, row.items, mappers.grocery_item_rows(grocery_list))
