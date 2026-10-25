from datetime import date, timedelta
from uuid import uuid4

import pytest

from mealiq.domain import (
    Ingredient,
    IngredientRequirement,
    MealPlan,
    MealType,
    NutritionSummary,
    PlannedMeal,
    Quantity,
    Recipe,
)
from mealiq.services.nutrition import NutritionService

# Round numbers so expected totals can be checked by hand.
RICE = Ingredient("rice", "grain", nutrition_per_100g=NutritionSummary(360, 7, 80, 1), grams_per_ml=0.8)
EGG = Ingredient("egg", "egg", nutrition_per_100g=NutritionSummary(140, 12.5, 1, 10), grams_per_unit=50)
OIL = Ingredient("oil", "oil", nutrition_per_100g=NutritionSummary(900, 0, 0, 100), grams_per_ml=0.9)
NO_DATA = Ingredient("mystery spice", "spice")
NO_UNIT_WEIGHT = Ingredient("lettuce", "vegetable", nutrition_per_100g=NutritionSummary(15, 1, 3, 0))

service = NutritionService()


def recipe(*requirements, servings=2):
    return Recipe("test", {MealType.DINNER}, prep_minutes=5, cook_minutes=10, servings=servings,
                  ingredients=list(requirements))


def req(ingredient, amount, unit, optional=False):
    return IngredientRequirement(ingredient, Quantity(amount, unit), optional)


def assert_summary(actual, calories, protein, carbs, fat, complete=True):
    assert actual.calories == pytest.approx(calories)
    assert actual.protein_g == pytest.approx(protein)
    assert actual.carbs_g == pytest.approx(carbs)
    assert actual.fat_g == pytest.approx(fat)
    assert actual.complete is complete


class TestCalculateNutrition:
    def test_mass_and_count_quantities(self):
        # 200 g rice = 720 kcal; 2 eggs = 100 g = 140 kcal; whole recipe = 860 kcal for 2 servings
        r = recipe(req(RICE, 200, "g"), req(EGG, 2, "unit"))
        assert_summary(service.calculate_nutrition(r, 2), 860, 26.5, 161, 12)
        assert_summary(service.calculate_nutrition(r, 1), 430, 13.25, 80.5, 6)

    def test_volume_uses_catalogue_density(self):
        # 1 tbsp oil = 14.787 ml * 0.9 g/ml = 13.31 g
        r = recipe(req(OIL, 1, "tbsp"), servings=1)
        grams = 14.78676478125 * 0.9
        assert_summary(service.calculate_nutrition(r, 1), 9 * grams, 0, 0, grams)

    def test_other_units_are_converted_first(self):
        r = recipe(req(RICE, 0.2, "kg"), servings=1)
        assert service.calculate_nutrition(r, 1).calories == pytest.approx(720)

    def test_missing_nutrition_marks_incomplete_and_keeps_the_rest(self):
        r = recipe(req(RICE, 100, "g"), req(NO_DATA, 5, "g"), servings=1)
        assert_summary(service.calculate_nutrition(r, 1), 360, 7, 80, 1, complete=False)

    def test_unconvertible_quantity_marks_incomplete(self):
        r = recipe(req(RICE, 100, "g"), req(NO_UNIT_WEIGHT, 1, "unit"), servings=1)
        assert_summary(service.calculate_nutrition(r, 1), 360, 7, 80, 1, complete=False)

    def test_optional_ingredients_are_left_out(self):
        r = recipe(req(RICE, 100, "g"), req(OIL, 10, "g", optional=True), servings=1)
        assert service.calculate_nutrition(r, 1).calories == pytest.approx(360)

    def test_zero_servings_is_zero(self):
        assert_summary(service.calculate_nutrition(recipe(req(RICE, 100, "g")), 0), 0, 0, 0, 0)

    def test_negative_servings_rejected(self):
        with pytest.raises(ValueError):
            service.calculate_nutrition(recipe(req(RICE, 100, "g")), -1)


class TestPlanTotals:
    def test_meals_and_daily_totals(self):
        start = date(2026, 10, 26)
        rice_bowl = recipe(req(RICE, 100, "g"), servings=1)       # 360 kcal per serving
        eggs = recipe(req(EGG, 2, "unit"), servings=1)             # 140 kcal per serving
        plan = MealPlan(
            profile_id=uuid4(),
            start_date=start,
            days=3,
            meals=[
                PlannedMeal(start, MealType.BREAKFAST, eggs),
                PlannedMeal(start, MealType.DINNER, rice_bowl, servings=2),
                PlannedMeal(start + timedelta(days=2), MealType.LUNCH, rice_bowl),
            ],
        )
        assert service.calculate_meals_nutrition(plan.meals).calories == pytest.approx(140 + 720 + 360)

        totals = service.daily_totals(plan)
        assert list(totals) == [start, start + timedelta(days=1), start + timedelta(days=2)]
        assert totals[start].calories == pytest.approx(860)
        assert_summary(totals[start + timedelta(days=1)], 0, 0, 0, 0)
        assert totals[start + timedelta(days=2)].calories == pytest.approx(360)

    def test_incomplete_meal_marks_its_day_incomplete(self):
        start = date(2026, 10, 26)
        plan = MealPlan(profile_id=uuid4(), start_date=start, days=1,
                        meals=[PlannedMeal(start, MealType.DINNER, recipe(req(NO_DATA, 1, "g"), servings=1))])
        assert service.daily_totals(plan)[start].complete is False
