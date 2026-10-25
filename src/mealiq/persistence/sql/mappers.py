"""Conversion between SQLAlchemy rows and domain objects.

A RowMapper instance is used for one load, so a recipe or ingredient referenced many
times in one plan becomes one shared domain object.
"""

from __future__ import annotations

from uuid import UUID

from mealiq.domain import (
    DayOfWeek,
    GroceryItem,
    GroceryList,
    Ingredient,
    IngredientRequirement,
    MealPlan,
    MealType,
    NutritionSummary,
    Pantry,
    PantryItem,
    PlannedMeal,
    PlanStatus,
    Quantity,
    Recipe,
    RecipeSource,
    UserProfile,
    WeightGoal,
)
from mealiq.persistence.sql.tables import (
    GroceryItemRow,
    GroceryListRow,
    IngredientRow,
    MealPlanRow,
    PantryItemRow,
    PantryRow,
    PlannedMealRow,
    ProfileRow,
    RecipeIngredientRow,
    RecipeRow,
)

_MEAL_ORDER = {t: i for i, t in enumerate(MealType)}


class RowMapper:
    def __init__(self) -> None:
        self._ingredients: dict[UUID, Ingredient] = {}
        self._recipes: dict[UUID, Recipe] = {}

    # --- rows -> domain -------------------------------------------------------

    def ingredient(self, row: IngredientRow) -> Ingredient:
        if row.id not in self._ingredients:
            self._ingredients[row.id] = Ingredient(
                ingredient_id=row.id,
                name=row.name,
                category=row.category,
                allergens=set(row.allergens or []),
                nutrition_per_100g=_nutrition(row),
                grams_per_unit=row.grams_per_unit,
                grams_per_ml=row.grams_per_ml,
                fdc_id=row.fdc_id,
            )
        return self._ingredients[row.id]

    def recipe(self, row: RecipeRow) -> Recipe:
        if row.id not in self._recipes:
            self._recipes[row.id] = Recipe(
                recipe_id=row.id,
                name=row.name,
                meal_types={MealType(t) for t in row.meal_types},
                prep_minutes=row.prep_minutes,
                cook_minutes=row.cook_minutes,
                servings=row.servings,
                ingredients=[
                    IngredientRequirement(self.ingredient(r.ingredient), Quantity(r.amount, r.unit), r.optional)
                    for r in row.ingredients
                ],
                tags=set(row.tags or []),
                source=RecipeSource(row.source),
                instructions=list(row.instructions or []),
            )
        return self._recipes[row.id]

    def profile(self, row: ProfileRow) -> UserProfile:
        return UserProfile(
            profile_id=row.id,
            goal=WeightGoal(row.goal),
            daily_calorie_target=row.daily_calorie_target,
            daily_protein_target_g=row.daily_protein_target_g,
            dietary_restrictions=set(row.dietary_restrictions or []),
            allergies=set(row.allergies or []),
            disliked_foods=set(row.disliked_foods or []),
            preferred_foods=set(row.preferred_foods or []),
            meals_per_day=row.meals_per_day,
            weekly_budget=row.weekly_budget,
            max_cook_minutes={DayOfWeek(d): m for d, m in (row.max_cook_minutes or {}).items()},
        )

    def pantry(self, row: PantryRow) -> Pantry:
        pantry = Pantry(profile_id=row.profile_id, pantry_id=row.id)
        for item in row.items:
            pantry.update_item(
                PantryItem(self.ingredient(item.ingredient), Quantity(item.amount, item.unit), item.expiry_date)
            )
        return pantry

    def meal_plan(self, row: MealPlanRow) -> MealPlan:
        meals = [
            PlannedMeal(
                meal_id=m.id,
                date=m.date,
                meal_type=MealType(m.meal_type),
                recipe=self.recipe(m.recipe),
                servings=m.servings,
            )
            for m in row.meals
        ]
        meals.sort(key=lambda m: (m.date, _MEAL_ORDER[m.meal_type]))
        return MealPlan(
            plan_id=row.id,
            profile_id=row.profile_id,
            start_date=row.start_date,
            days=row.days,
            meals=meals,
            status=PlanStatus(row.status),
        )

    def grocery_list(self, row: GroceryListRow) -> GroceryList:
        return GroceryList(
            list_id=row.id,
            plan_id=row.plan_id,
            estimated_cost=row.estimated_cost,
            stale=row.stale,
            items=[
                GroceryItem(
                    ingredient=self.ingredient(i.ingredient),
                    required=Quantity(i.required_amount, i.required_unit),
                    in_pantry=Quantity(i.in_pantry_amount, i.in_pantry_unit),
                    to_buy=Quantity(i.to_buy_amount, i.to_buy_unit),
                    estimated_price=i.estimated_price,
                )
                for i in row.items
            ],
        )


# --- domain -> rows (fields only; children are built by the repositories) -------


def fill_ingredient_row(row: IngredientRow, ingredient: Ingredient) -> None:
    row.name = ingredient.name.strip().lower()
    row.category = ingredient.category
    row.allergens = sorted(ingredient.allergens)
    n = ingredient.nutrition_per_100g
    row.calories = n.calories if n else None
    row.protein_g = n.protein_g if n else None
    row.carbs_g = n.carbs_g if n else None
    row.fat_g = n.fat_g if n else None
    row.grams_per_unit = ingredient.grams_per_unit
    row.grams_per_ml = ingredient.grams_per_ml
    row.fdc_id = ingredient.fdc_id


def fill_recipe_row(row: RecipeRow, recipe: Recipe) -> None:
    row.name = recipe.name
    row.meal_types = sorted(t.value for t in recipe.meal_types)
    row.prep_minutes = recipe.prep_minutes
    row.cook_minutes = recipe.cook_minutes
    row.servings = recipe.servings
    row.tags = sorted(recipe.tags)
    row.source = recipe.source.value
    row.instructions = list(recipe.instructions)


def recipe_ingredient_rows(recipe: Recipe) -> list[RecipeIngredientRow]:
    return [
        RecipeIngredientRow(
            position=i,
            ingredient_id=req.ingredient.ingredient_id,
            amount=req.quantity.amount,
            unit=req.quantity.unit,
            optional=req.optional,
        )
        for i, req in enumerate(recipe.ingredients)
    ]


def fill_profile_row(row: ProfileRow, profile: UserProfile) -> None:
    row.goal = profile.goal.value
    row.daily_calorie_target = profile.daily_calorie_target
    row.daily_protein_target_g = profile.daily_protein_target_g
    row.dietary_restrictions = sorted(profile.dietary_restrictions)
    row.allergies = sorted(profile.allergies)
    row.disliked_foods = sorted(profile.disliked_foods)
    row.preferred_foods = sorted(profile.preferred_foods)
    row.meals_per_day = profile.meals_per_day
    row.weekly_budget = profile.weekly_budget
    row.max_cook_minutes = {d.value: m for d, m in profile.max_cook_minutes.items()}


def pantry_item_rows(pantry: Pantry) -> list[PantryItemRow]:
    return [
        PantryItemRow(
            ingredient_id=item.ingredient.ingredient_id,
            amount=item.quantity.amount,
            unit=item.quantity.unit,
            expiry_date=item.expiry_date,
        )
        for item in pantry.items.values()
    ]


def planned_meal_rows(plan: MealPlan) -> list[PlannedMealRow]:
    return [
        PlannedMealRow(
            id=m.meal_id,
            date=m.date,
            meal_type=m.meal_type.value,
            recipe_id=m.recipe.recipe_id,
            servings=m.servings,
        )
        for m in plan.meals
    ]


def grocery_item_rows(grocery_list: GroceryList) -> list[GroceryItemRow]:
    return [
        GroceryItemRow(
            position=i,
            ingredient_id=item.ingredient.ingredient_id,
            required_amount=item.required.amount,
            required_unit=item.required.unit,
            in_pantry_amount=item.in_pantry.amount,
            in_pantry_unit=item.in_pantry.unit,
            to_buy_amount=item.to_buy.amount,
            to_buy_unit=item.to_buy.unit,
            estimated_price=item.estimated_price,
        )
        for i, item in enumerate(grocery_list.items)
    ]


def _nutrition(row: IngredientRow) -> NutritionSummary | None:
    values = (row.calories, row.protein_g, row.carbs_g, row.fat_g)
    if all(v is None for v in values):
        return None
    return NutritionSummary(
        calories=row.calories or 0.0,
        protein_g=row.protein_g or 0.0,
        carbs_g=row.carbs_g or 0.0,
        fat_g=row.fat_g or 0.0,
        complete=all(v is not None for v in values),
    )
