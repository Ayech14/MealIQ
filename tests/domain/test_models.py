from datetime import date, timedelta
from decimal import Decimal
from uuid import uuid4

import pytest

from mealiq.domain import (
    DayOfWeek,
    GroceryItem,
    GroceryList,
    Ingredient,
    MealPlan,
    MealType,
    NutritionSummary,
    Pantry,
    PantryItem,
    PlannedMeal,
    Quantity,
    Recipe,
    UserProfile,
)

TODAY = date(2026, 10, 24)

chicken = Ingredient("chicken breast", "poultry")
salmon = Ingredient("salmon", "fish", allergens={"fish"})
milk = Ingredient("milk", "dairy", allergens={"milk"})
bread = Ingredient("bread", "grain", allergens={"gluten"})
peanuts = Ingredient("peanuts", "nut", allergens={"Peanut"})
rice = Ingredient("rice", "grain")


def recipe(name: str, minutes: int = 20) -> Recipe:
    return Recipe(name, {MealType.DINNER}, prep_minutes=minutes // 2, cook_minutes=minutes - minutes // 2, servings=2)


class TestUserProfile:
    def test_allergy_blocks_ingredient(self):
        profile = UserProfile(allergies={"peanut"})
        assert not profile.is_allowed(peanuts)
        assert profile.is_allowed(rice)

    @pytest.mark.parametrize(
        ("restriction", "blocked", "allowed"),
        [
            ("vegetarian", [chicken, salmon], [milk, rice]),
            ("pescatarian", [chicken], [salmon, milk]),
            ("vegan", [chicken, salmon, milk], [rice, bread]),
            ("Gluten Free", [bread], [rice, chicken]),
            ("dairy_free", [milk], [rice]),
            ("nut-free", [peanuts], [rice]),
        ],
    )
    def test_dietary_restrictions(self, restriction, blocked, allowed):
        profile = UserProfile(dietary_restrictions={restriction})
        assert all(not profile.is_allowed(i) for i in blocked)
        assert all(profile.is_allowed(i) for i in allowed)

    def test_dislikes_do_not_block(self):
        assert UserProfile(disliked_foods={"rice"}).is_allowed(rice)

    def test_max_minutes_per_day(self):
        profile = UserProfile(max_cook_minutes={DayOfWeek.MON: 30})
        assert profile.max_minutes_for(DayOfWeek.MON) == 30
        assert profile.max_minutes_for(DayOfWeek.TUE) is None

    def test_day_of_week_from_date(self):
        assert DayOfWeek.of(TODAY) is DayOfWeek.SAT


class TestPantry:
    def test_available_quantity_excludes_expired_items(self):
        pantry = Pantry(profile_id=uuid4())
        pantry.update_item(PantryItem(milk, Quantity(1, "l"), expiry_date=TODAY - timedelta(days=1)))
        pantry.update_item(PantryItem(rice, Quantity(500, "g"), expiry_date=TODAY))
        assert pantry.available_quantity(milk, TODAY).amount == 0
        assert pantry.available_quantity(rice, TODAY) == Quantity(500, "g")
        assert len(pantry.items) == 2  # expired item is still listed

    def test_missing_item_is_zero(self):
        assert Pantry(profile_id=uuid4()).available_quantity(rice, TODAY).amount == 0

    def test_update_replaces_and_remove_deletes(self):
        pantry = Pantry(profile_id=uuid4())
        pantry.update_item(PantryItem(rice, Quantity(500, "g")))
        pantry.update_item(PantryItem(rice, Quantity(1, "kg")))
        assert pantry.available_quantity(rice, TODAY) == Quantity(1, "kg")
        pantry.remove_item(rice.ingredient_id)
        assert pantry.items == {}


class TestMealPlan:
    def test_meals_on_find_and_replace(self):
        quick, slow = recipe("stir fry", 20), recipe("roast", 90)
        meal = PlannedMeal(TODAY, MealType.DINNER, slow)
        plan = MealPlan(profile_id=uuid4(), start_date=TODAY, days=7, meals=[meal])
        assert plan.meals_on(TODAY) == [meal]
        assert plan.meals_on(TODAY + timedelta(days=1)) == []
        plan.replace_meal(meal.meal_id, quick)
        assert plan.find_meal(meal.meal_id).recipe is quick

    def test_find_unknown_meal_raises(self):
        plan = MealPlan(profile_id=uuid4(), start_date=TODAY, days=7)
        with pytest.raises(KeyError):
            plan.find_meal(uuid4())

    def test_recipe_total_minutes(self):
        assert recipe("x", 45).total_minutes() == 45


def test_ingredient_to_grams():
    egg = Ingredient("egg", "egg", grams_per_unit=50)
    oil = Ingredient("oil", "oil", grams_per_ml=0.9)
    assert egg.to_grams(Quantity(2, "unit")) == 100
    assert egg.to_grams(Quantity(1, "dozen")) == 600
    assert egg.to_grams(Quantity(0.1, "kg")) == pytest.approx(100)
    assert egg.to_grams(Quantity(1, "cup")) is None  # no volume weight in the catalogue
    assert oil.to_grams(Quantity(100, "ml")) == pytest.approx(90)
    assert oil.to_grams(Quantity(1, "unit")) is None
    assert oil.to_grams(Quantity(0, "unit")) == 0


def test_nutrition_add_propagates_incomplete():
    a = NutritionSummary(100, 10, 5, 2)
    b = NutritionSummary(50, 1, 1, 1, complete=False)
    total = a.add(b)
    assert (total.calories, total.protein_g, total.complete) == (150, 11, False)
    assert a.scaled(2).calories == 200


def test_grocery_list_total_and_partial():
    priced = GroceryItem(rice, Quantity(1, "kg"), Quantity(0, "g"), Quantity(1, "kg"), Decimal("3.50"))
    unpriced = GroceryItem(milk, Quantity(1, "l"), Quantity(0, "l"), Quantity(1, "l"))
    grocery = GroceryList(plan_id=uuid4(), items=[priced, unpriced])
    assert grocery.total() == Decimal("3.50")
    assert grocery.is_partial()
    grocery.mark_stale()
    assert grocery.stale
