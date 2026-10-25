from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy.exc import IntegrityError

from mealiq.contracts import RecipeQuery
from mealiq.domain import (
    DayOfWeek,
    GroceryItem,
    GroceryList,
    Ingredient,
    IngredientRequirement,
    MealPlan,
    MealType,
    NutritionSummary,
    PantryItem,
    PlannedMeal,
    PlanStatus,
    Quantity,
    Recipe,
    RecipeSource,
    UserProfile,
    WeightGoal,
)
from mealiq.persistence.sql import (
    SqlGroceryListRepository,
    SqlIngredientRepository,
    SqlMealPlanRepository,
    SqlPantryRepository,
    SqlProfileRepository,
    SqlRecipeRepository,
)

START = date(2026, 10, 26)


@pytest.fixture
def repos(session_factory):
    class Repos:
        ingredients = SqlIngredientRepository(session_factory)
        recipes = SqlRecipeRepository(session_factory)
        profiles = SqlProfileRepository(session_factory)
        pantries = SqlPantryRepository(session_factory)
        plans = SqlMealPlanRepository(session_factory)
        groceries = SqlGroceryListRepository(session_factory)

    return Repos()


@pytest.fixture
def catalogue(repos):
    items = {
        "chicken": Ingredient("Chicken Breast", "poultry", nutrition_per_100g=NutritionSummary(165, 31, 0, 3.6)),
        "tofu": Ingredient("tofu", "legume", allergens={"soy"}, nutrition_per_100g=NutritionSummary(76, 8, 1.9, 4.8)),
        "rice": Ingredient("rice", "grain", nutrition_per_100g=NutritionSummary(130, 2.7, 28, 0.3)),
        "egg": Ingredient("egg", "egg", allergens={"egg"}),
    }
    for ingredient in items.values():
        repos.ingredients.save(ingredient)
    repos.ingredients.save(items["chicken"], substitutes=[items["tofu"]])
    return items


@pytest.fixture
def profile(repos):
    profile = UserProfile(
        goal=WeightGoal.GAIN,
        daily_protein_target_g=140,
        allergies={"peanut"},
        dietary_restrictions={"gluten-free"},
        weekly_budget=Decimal("100.00"),
        max_cook_minutes={DayOfWeek.MON: 20, DayOfWeek.WED: 45},
    )
    repos.profiles.save(profile)
    return profile


def make_recipe(name, catalogue, *keys, minutes=30, meal_type=MealType.DINNER, source=RecipeSource.CURATED):
    return Recipe(
        name=name,
        meal_types={meal_type},
        prep_minutes=minutes // 3,
        cook_minutes=minutes - minutes // 3,
        servings=2,
        ingredients=[IngredientRequirement(catalogue[k], Quantity(200, "g")) for k in keys],
        source=source,
        instructions=["Cook.", "Serve."],
    )


class TestIngredients:
    def test_round_trip_and_case_insensitive_name(self, repos, catalogue):
        found = repos.ingredients.find_by_name("  CHICKEN breast ")
        assert found.ingredient_id == catalogue["chicken"].ingredient_id
        assert found.nutrition_per_100g == NutritionSummary(165, 31, 0, 3.6)
        assert repos.ingredients.find_by_id(catalogue["tofu"].ingredient_id).allergens == {"soy"}

    def test_missing_nutrition_stays_missing(self, repos, catalogue):
        assert repos.ingredients.find_by_name("egg").nutrition_per_100g is None

    def test_substitutes(self, repos, catalogue):
        subs = repos.ingredients.find_substitutes(catalogue["chicken"])
        assert [s.name for s in subs] == ["tofu"]
        assert repos.ingredients.find_substitutes(catalogue["rice"]) == []

    def test_names_are_unique(self, repos, catalogue):
        with pytest.raises(IntegrityError):
            repos.ingredients.save(Ingredient("chicken breast", "poultry"))


class TestRecipes:
    def test_round_trip_keeps_ingredient_order_and_fields(self, repos, catalogue):
        recipe = make_recipe("Chicken rice", catalogue, "chicken", "rice", "egg")
        repos.recipes.save(recipe)
        loaded = repos.recipes.find_by_id(recipe.recipe_id)
        assert [r.ingredient.name for r in loaded.ingredients] == ["chicken breast", "rice", "egg"]
        assert loaded.ingredients[0].quantity == Quantity(200, "g")
        assert loaded.instructions == ["Cook.", "Serve."]
        assert loaded.total_minutes() == 30

    def test_update_replaces_ingredients(self, repos, catalogue):
        recipe = make_recipe("Bowl", catalogue, "chicken", "rice")
        repos.recipes.save(recipe)
        recipe.ingredients = [IngredientRequirement(catalogue["tofu"], Quantity(1, "unit"))]
        repos.recipes.save(recipe)
        loaded = repos.recipes.find_by_id(recipe.recipe_id)
        assert [r.ingredient.name for r in loaded.ingredients] == ["tofu"]

    def test_rejects_ingredient_outside_catalogue(self, repos, catalogue):
        recipe = make_recipe("Mystery", catalogue, "rice")
        recipe.ingredients.append(IngredientRequirement(Ingredient("unobtainium", "other"), Quantity(1, "g")))
        with pytest.raises(ValueError, match="unobtainium"):
            repos.recipes.save(recipe)
        assert repos.recipes.find_by_id(recipe.recipe_id) is None

    def test_search_filters(self, repos, catalogue):
        repos.recipes.save(make_recipe("Quick chicken", catalogue, "chicken", "rice", minutes=15))
        repos.recipes.save(make_recipe("Slow chicken", catalogue, "chicken", minutes=90))
        repos.recipes.save(make_recipe("Tofu breakfast", catalogue, "tofu", "egg", meal_type=MealType.BREAKFAST))
        repos.recipes.save(make_recipe("AI rice", catalogue, "rice", source=RecipeSource.GENERATED))

        def names(**kwargs):
            return [r.name for r in repos.recipes.search(RecipeQuery(**kwargs))]

        assert names() == ["Quick chicken", "Slow chicken", "Tofu breakfast"]
        assert names(allow_generated=True) == ["AI rice", "Quick chicken", "Slow chicken", "Tofu breakfast"]
        assert names(max_minutes=20) == ["Quick chicken"]
        assert names(meal_type=MealType.BREAKFAST) == ["Tofu breakfast"]
        assert names(include_ingredients=["chicken"]) == ["Quick chicken", "Slow chicken"]
        assert names(include_ingredients=["egg", "rice"]) == ["Quick chicken", "Tofu breakfast"]
        assert names(exclude_ingredients=["rice"]) == ["Slow chicken", "Tofu breakfast"]


class TestProfiles:
    def test_round_trip(self, repos, profile):
        loaded = repos.profiles.find_by_id(profile.profile_id)
        assert loaded == profile
        assert loaded.max_minutes_for(DayOfWeek.MON) == 20
        assert loaded.weekly_budget == Decimal("100.00")

    def test_update(self, repos, profile):
        profile.meals_per_day = 4
        profile.disliked_foods = {"mushroom"}
        repos.profiles.save(profile)
        loaded = repos.profiles.find_by_id(profile.profile_id)
        assert (loaded.meals_per_day, loaded.disliked_foods) == (4, {"mushroom"})


class TestPantry:
    def test_new_profile_gets_empty_pantry(self, repos, profile):
        pantry = repos.pantries.find_by_profile_id(profile.profile_id)
        assert pantry.items == {}

    def test_save_update_and_remove(self, repos, profile, catalogue):
        pantry = repos.pantries.find_by_profile_id(profile.profile_id)
        pantry.update_item(PantryItem(catalogue["egg"], Quantity(12, "unit"), expiry_date=date(2026, 11, 1)))
        pantry.update_item(PantryItem(catalogue["rice"], Quantity(1, "kg")))
        repos.pantries.save(pantry)

        loaded = repos.pantries.find_by_profile_id(profile.profile_id)
        assert loaded.available_quantity(catalogue["egg"], START) == Quantity(12, "unit")
        assert loaded.items[catalogue["egg"].ingredient_id].expiry_date == date(2026, 11, 1)

        loaded.update_item(PantryItem(catalogue["egg"], Quantity(6, "unit")))
        loaded.remove_item(catalogue["rice"].ingredient_id)
        repos.pantries.save(loaded)
        again = repos.pantries.find_by_profile_id(profile.profile_id)
        assert list(again.items) == [catalogue["egg"].ingredient_id]
        assert again.available_quantity(catalogue["egg"], START) == Quantity(6, "unit")


class TestMealPlans:
    def _plan(self, repos, profile, catalogue, status=PlanStatus.ACTIVE, start=START):
        dinner = make_recipe("Chicken rice", catalogue, "chicken", "rice")
        breakfast = make_recipe("Egg rice", catalogue, "egg", "rice", meal_type=MealType.BREAKFAST)
        repos.recipes.save(dinner)
        repos.recipes.save(breakfast)
        plan = MealPlan(
            profile_id=profile.profile_id,
            start_date=start,
            days=2,
            status=status,
            meals=[
                PlannedMeal(start, MealType.DINNER, dinner, servings=2),
                PlannedMeal(start, MealType.BREAKFAST, breakfast),
                PlannedMeal(date(2026, 10, 27), MealType.DINNER, dinner),
            ],
        )
        repos.plans.save(plan)
        return plan, dinner, breakfast

    def test_round_trip_orders_meals_and_shares_recipes(self, repos, profile, catalogue):
        plan, _, _ = self._plan(repos, profile, catalogue)
        loaded = repos.plans.find_by_id(plan.plan_id)
        assert [(m.date.day, m.meal_type) for m in loaded.meals] == [
            (26, MealType.BREAKFAST),
            (26, MealType.DINNER),
            (27, MealType.DINNER),
        ]
        assert loaded.meals[1].recipe is loaded.meals[2].recipe
        assert loaded.meals[1].servings == 2

    def test_replace_meal_persists(self, repos, profile, catalogue):
        plan, dinner, breakfast = self._plan(repos, profile, catalogue)
        target = plan.meals[2]
        plan.replace_meal(target.meal_id, breakfast)
        repos.plans.save(plan)
        loaded = repos.plans.find_by_id(plan.plan_id)
        assert loaded.find_meal(target.meal_id).recipe.name == "Egg rice"
        assert len(loaded.meals) == 3

    def test_find_active(self, repos, profile, catalogue):
        self._plan(repos, profile, catalogue, status=PlanStatus.ARCHIVED)
        assert repos.plans.find_active_by_profile_id(profile.profile_id) is None
        active, _, _ = self._plan(repos, profile, catalogue, start=date(2026, 11, 2))
        assert repos.plans.find_active_by_profile_id(profile.profile_id).plan_id == active.plan_id


class TestGroceryLists:
    def test_round_trip_and_mark_stale(self, repos, profile, catalogue):
        plan = MealPlan(profile_id=profile.profile_id, start_date=START, days=7)
        repos.plans.save(plan)
        grocery = GroceryList(
            plan_id=plan.plan_id,
            estimated_cost=Decimal("12.40"),
            items=[
                GroceryItem(catalogue["rice"], Quantity(1, "kg"), Quantity(200, "g"), Quantity(800, "g"), Decimal("3.20")),
                GroceryItem(catalogue["egg"], Quantity(6, "unit"), Quantity(0, "unit"), Quantity(6, "unit")),
            ],
        )
        repos.groceries.save(grocery)

        loaded = repos.groceries.find_by_plan_id(plan.plan_id)
        assert [i.ingredient.name for i in loaded.items] == ["rice", "egg"]
        assert loaded.items[0].to_buy == Quantity(800, "g")
        assert loaded.items[1].estimated_price is None
        assert loaded.is_partial()

        loaded.mark_stale()
        repos.groceries.save(loaded)
        assert repos.groceries.find_by_plan_id(plan.plan_id).stale
