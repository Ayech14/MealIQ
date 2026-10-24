"""Domain entities and value objects from the Stage 1 domain class diagram.

These are plain dataclasses with no framework or persistence types, so services and
tests can use them directly. Persistence maps them in mealiq.persistence.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from uuid import UUID, uuid4

from mealiq.domain.diet import DIET_RULES, normalize_tag
from mealiq.domain.enums import DayOfWeek, MealType, PlanStatus, RecipeSource, WeightGoal
from mealiq.domain.quantity import Quantity


@dataclass(frozen=True)
class NutritionSummary:
    """Calories and macros. `complete` is False when any contributing value was missing (D09)."""

    calories: float = 0.0
    protein_g: float = 0.0
    carbs_g: float = 0.0
    fat_g: float = 0.0
    complete: bool = True

    def add(self, other: NutritionSummary) -> NutritionSummary:
        return NutritionSummary(
            calories=self.calories + other.calories,
            protein_g=self.protein_g + other.protein_g,
            carbs_g=self.carbs_g + other.carbs_g,
            fat_g=self.fat_g + other.fat_g,
            complete=self.complete and other.complete,
        )

    def scaled(self, factor: float) -> NutritionSummary:
        return NutritionSummary(
            calories=self.calories * factor,
            protein_g=self.protein_g * factor,
            carbs_g=self.carbs_g * factor,
            fat_g=self.fat_g * factor,
            complete=self.complete,
        )


@dataclass
class Ingredient:
    name: str
    category: str
    allergens: set[str] = field(default_factory=set)
    nutrition_per_100g: NutritionSummary | None = None
    ingredient_id: UUID = field(default_factory=uuid4)

    def __post_init__(self) -> None:
        self.category = normalize_tag(self.category)
        self.allergens = {normalize_tag(a) for a in self.allergens}


@dataclass
class IngredientRequirement:
    ingredient: Ingredient
    quantity: Quantity
    optional: bool = False


@dataclass
class Recipe:
    name: str
    meal_types: set[MealType]
    prep_minutes: int
    cook_minutes: int
    servings: int
    ingredients: list[IngredientRequirement] = field(default_factory=list)
    tags: set[str] = field(default_factory=set)
    source: RecipeSource = RecipeSource.CURATED
    instructions: list[str] = field(default_factory=list)
    recipe_id: UUID = field(default_factory=uuid4)

    def total_minutes(self) -> int:
        return self.prep_minutes + self.cook_minutes


@dataclass
class UserProfile:
    goal: WeightGoal = WeightGoal.MAINTAIN
    daily_calorie_target: int | None = None
    daily_protein_target_g: int | None = None
    dietary_restrictions: set[str] = field(default_factory=set)
    allergies: set[str] = field(default_factory=set)
    disliked_foods: set[str] = field(default_factory=set)
    preferred_foods: set[str] = field(default_factory=set)
    meals_per_day: int = 3
    weekly_budget: Decimal = Decimal("0")
    max_cook_minutes: dict[DayOfWeek, int] = field(default_factory=dict)
    profile_id: UUID = field(default_factory=uuid4)

    def __post_init__(self) -> None:
        self.dietary_restrictions = {normalize_tag(r) for r in self.dietary_restrictions}
        self.allergies = {normalize_tag(a) for a in self.allergies}

    def is_allowed(self, ingredient: Ingredient) -> bool:
        """Hard-constraint check only: allergies and dietary restrictions (D04).

        Dislikes are soft constraints and are not checked here.
        """
        if ingredient.allergens & self.allergies:
            return False
        for restriction in self.dietary_restrictions:
            rule = DIET_RULES.get(restriction)
            if rule is None:
                continue
            if ingredient.category in rule.excluded_categories:
                return False
            if ingredient.allergens & rule.excluded_allergens:
                return False
        return True

    def max_minutes_for(self, day: DayOfWeek) -> int | None:
        return self.max_cook_minutes.get(day)


@dataclass
class PantryItem:
    ingredient: Ingredient
    quantity: Quantity
    expiry_date: date | None = None

    def is_expired(self, on: date) -> bool:
        return self.expiry_date is not None and self.expiry_date < on


@dataclass
class Pantry:
    """One entry per ingredient; updating an ingredient replaces its entry."""

    profile_id: UUID
    items: dict[UUID, PantryItem] = field(default_factory=dict)
    pantry_id: UUID = field(default_factory=uuid4)

    def available_quantity(self, ingredient: Ingredient, on: date) -> Quantity:
        """Usable stock on a date. Expired items stay listed but count as zero."""
        item = self.items.get(ingredient.ingredient_id)
        if item is None or item.is_expired(on):
            unit = item.quantity.unit if item else "g"
            return Quantity(0.0, unit)
        return item.quantity

    def update_item(self, item: PantryItem) -> None:
        self.items[item.ingredient.ingredient_id] = item

    def remove_item(self, ingredient_id: UUID) -> None:
        self.items.pop(ingredient_id, None)


@dataclass
class PlannedMeal:
    date: date
    meal_type: MealType
    recipe: Recipe
    servings: int = 1
    meal_id: UUID = field(default_factory=uuid4)


@dataclass
class MealPlan:
    profile_id: UUID
    start_date: date
    days: int
    meals: list[PlannedMeal] = field(default_factory=list)
    status: PlanStatus = PlanStatus.DRAFT
    plan_id: UUID = field(default_factory=uuid4)

    def meals_on(self, on: date) -> list[PlannedMeal]:
        return [m for m in self.meals if m.date == on]

    def find_meal(self, meal_id: UUID) -> PlannedMeal:
        for meal in self.meals:
            if meal.meal_id == meal_id:
                return meal
        raise KeyError(f"Meal {meal_id} is not in plan {self.plan_id}")

    def replace_meal(self, meal_id: UUID, recipe: Recipe) -> None:
        self.find_meal(meal_id).recipe = recipe


@dataclass
class GroceryItem:
    ingredient: Ingredient
    required: Quantity
    in_pantry: Quantity
    to_buy: Quantity
    estimated_price: Decimal | None = None


@dataclass
class GroceryList:
    plan_id: UUID
    items: list[GroceryItem] = field(default_factory=list)
    estimated_cost: Decimal = Decimal("0")
    stale: bool = False
    list_id: UUID = field(default_factory=uuid4)

    def mark_stale(self) -> None:
        self.stale = True

    def total(self) -> Decimal:
        """Sum of known item prices; items without a price are not counted."""
        return sum((i.estimated_price for i in self.items if i.estimated_price is not None), Decimal("0"))

    def is_partial(self) -> bool:
        """True when some item that must be bought has no price estimate."""
        return any(i.estimated_price is None and i.to_buy.amount > 0 for i in self.items)


@dataclass(frozen=True)
class BudgetStatus:
    estimate: Decimal
    budget: Decimal
    shortfall: Decimal
    within_budget: bool
    partial: bool
    cost_drivers: tuple[GroceryItem, ...] = ()
