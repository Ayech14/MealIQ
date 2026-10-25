"""NutritionService: deterministic calories and macros from the ingredient catalogue (F06, D09).

Each ingredient's per-100 g values are scaled by its quantity converted to grams. When
an ingredient has no nutrition data, or its quantity cannot be converted to grams, it
contributes nothing and the result is marked incomplete. Values are never estimated.
"""

from __future__ import annotations

from datetime import date, timedelta

from mealiq.domain import MealPlan, NutritionSummary, PlannedMeal, Recipe


class NutritionService:
    def calculate_nutrition(self, recipe: Recipe, servings: int) -> NutritionSummary:
        """Nutrition for `servings` portions of a recipe that yields `recipe.servings`.

        Optional ingredients are left out, because the recipe works without them.
        """
        if servings < 0:
            raise ValueError("servings cannot be negative")
        if recipe.servings <= 0:
            raise ValueError(f"Recipe {recipe.name!r} has no servings")
        total = NutritionSummary()
        for requirement in recipe.ingredients:
            if requirement.optional:
                continue
            per_100g = requirement.ingredient.nutrition_per_100g
            grams = requirement.ingredient.to_grams(requirement.quantity)
            if per_100g is None or grams is None:
                total = total.add(NutritionSummary(complete=False))
                continue
            total = total.add(per_100g.scaled(grams / 100))
        return total.scaled(servings / recipe.servings)

    def calculate_meals_nutrition(self, meals: list[PlannedMeal]) -> NutritionSummary:
        """Stage 1 calculateNutrition(meals): Python has no overloading, so it is named apart."""
        total = NutritionSummary()
        for meal in meals:
            total = total.add(self.calculate_nutrition(meal.recipe, meal.servings))
        return total

    def daily_totals(self, plan: MealPlan) -> dict[date, NutritionSummary]:
        """Totals for every day of the plan, including days without meals."""
        days = [plan.start_date + timedelta(days=i) for i in range(plan.days)]
        return {day: self.calculate_meals_nutrition(plan.meals_on(day)) for day in days}
