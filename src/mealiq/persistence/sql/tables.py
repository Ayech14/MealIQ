"""SQLAlchemy table rows. Kept separate from the domain classes, which stay framework-free;
mealiq.persistence.sql.mappers converts between the two.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from uuid import UUID

from sqlalchemy import JSON, Boolean, Date, Float, ForeignKey, Integer, Numeric, String, Uuid
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

MONEY = Numeric(10, 2, asdecimal=True)


class Base(DeclarativeBase):
    pass


class IngredientRow(Base):
    __tablename__ = "ingredients"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    name: Mapped[str] = mapped_column(String(120), unique=True, index=True)
    category: Mapped[str] = mapped_column(String(60))
    allergens: Mapped[list[str]] = mapped_column(JSON, default=list)
    # Per 100 g; NULL means the catalogue has no value (D09).
    calories: Mapped[float | None] = mapped_column(Float)
    protein_g: Mapped[float | None] = mapped_column(Float)
    carbs_g: Mapped[float | None] = mapped_column(Float)
    fat_g: Mapped[float | None] = mapped_column(Float)


class IngredientSubstituteRow(Base):
    __tablename__ = "ingredient_substitutes"

    ingredient_id: Mapped[UUID] = mapped_column(ForeignKey("ingredients.id", ondelete="CASCADE"), primary_key=True)
    substitute_id: Mapped[UUID] = mapped_column(ForeignKey("ingredients.id", ondelete="CASCADE"), primary_key=True)


class RecipeRow(Base):
    __tablename__ = "recipes"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    name: Mapped[str] = mapped_column(String(200), index=True)
    meal_types: Mapped[list[str]] = mapped_column(JSON)
    prep_minutes: Mapped[int] = mapped_column(Integer)
    cook_minutes: Mapped[int] = mapped_column(Integer)
    servings: Mapped[int] = mapped_column(Integer)
    tags: Mapped[list[str]] = mapped_column(JSON, default=list)
    source: Mapped[str] = mapped_column(String(20))
    instructions: Mapped[list[str]] = mapped_column(JSON, default=list)

    ingredients: Mapped[list[RecipeIngredientRow]] = relationship(
        cascade="all, delete-orphan", order_by="RecipeIngredientRow.position", lazy="selectin"
    )


class RecipeIngredientRow(Base):
    __tablename__ = "recipe_ingredients"

    recipe_id: Mapped[UUID] = mapped_column(ForeignKey("recipes.id", ondelete="CASCADE"), primary_key=True)
    position: Mapped[int] = mapped_column(Integer, primary_key=True)
    ingredient_id: Mapped[UUID] = mapped_column(ForeignKey("ingredients.id"))
    amount: Mapped[float] = mapped_column(Float)
    unit: Mapped[str] = mapped_column(String(20))
    optional: Mapped[bool] = mapped_column(Boolean, default=False)

    ingredient: Mapped[IngredientRow] = relationship(lazy="selectin")


class ProfileRow(Base):
    __tablename__ = "profiles"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    goal: Mapped[str] = mapped_column(String(20))
    daily_calorie_target: Mapped[int | None] = mapped_column(Integer)
    daily_protein_target_g: Mapped[int | None] = mapped_column(Integer)
    dietary_restrictions: Mapped[list[str]] = mapped_column(JSON, default=list)
    allergies: Mapped[list[str]] = mapped_column(JSON, default=list)
    disliked_foods: Mapped[list[str]] = mapped_column(JSON, default=list)
    preferred_foods: Mapped[list[str]] = mapped_column(JSON, default=list)
    meals_per_day: Mapped[int] = mapped_column(Integer)
    weekly_budget: Mapped[Decimal] = mapped_column(MONEY)
    max_cook_minutes: Mapped[dict[str, int]] = mapped_column(JSON, default=dict)


class PantryRow(Base):
    __tablename__ = "pantries"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    profile_id: Mapped[UUID] = mapped_column(ForeignKey("profiles.id", ondelete="CASCADE"), unique=True)

    items: Mapped[list[PantryItemRow]] = relationship(cascade="all, delete-orphan", lazy="selectin")


class PantryItemRow(Base):
    __tablename__ = "pantry_items"

    pantry_id: Mapped[UUID] = mapped_column(ForeignKey("pantries.id", ondelete="CASCADE"), primary_key=True)
    ingredient_id: Mapped[UUID] = mapped_column(ForeignKey("ingredients.id"), primary_key=True)
    amount: Mapped[float] = mapped_column(Float)
    unit: Mapped[str] = mapped_column(String(20))
    expiry_date: Mapped[date | None] = mapped_column(Date)

    ingredient: Mapped[IngredientRow] = relationship(lazy="selectin")


class MealPlanRow(Base):
    __tablename__ = "meal_plans"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    profile_id: Mapped[UUID] = mapped_column(ForeignKey("profiles.id", ondelete="CASCADE"), index=True)
    start_date: Mapped[date] = mapped_column(Date)
    days: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(20), index=True)

    meals: Mapped[list[PlannedMealRow]] = relationship(
        cascade="all, delete-orphan", order_by="PlannedMealRow.date", lazy="selectin"
    )


class PlannedMealRow(Base):
    __tablename__ = "planned_meals"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    plan_id: Mapped[UUID] = mapped_column(ForeignKey("meal_plans.id", ondelete="CASCADE"), index=True)
    date: Mapped[date] = mapped_column(Date)
    meal_type: Mapped[str] = mapped_column(String(20))
    recipe_id: Mapped[UUID] = mapped_column(ForeignKey("recipes.id"))
    servings: Mapped[int] = mapped_column(Integer)

    recipe: Mapped[RecipeRow] = relationship(lazy="selectin")


class GroceryListRow(Base):
    __tablename__ = "grocery_lists"

    id: Mapped[UUID] = mapped_column(Uuid, primary_key=True)
    plan_id: Mapped[UUID] = mapped_column(ForeignKey("meal_plans.id", ondelete="CASCADE"), unique=True)
    estimated_cost: Mapped[Decimal] = mapped_column(MONEY)
    stale: Mapped[bool] = mapped_column(Boolean, default=False)

    items: Mapped[list[GroceryItemRow]] = relationship(
        cascade="all, delete-orphan", order_by="GroceryItemRow.position", lazy="selectin"
    )


class GroceryItemRow(Base):
    __tablename__ = "grocery_items"

    list_id: Mapped[UUID] = mapped_column(ForeignKey("grocery_lists.id", ondelete="CASCADE"), primary_key=True)
    position: Mapped[int] = mapped_column(Integer, primary_key=True)
    ingredient_id: Mapped[UUID] = mapped_column(ForeignKey("ingredients.id"))
    required_amount: Mapped[float] = mapped_column(Float)
    required_unit: Mapped[str] = mapped_column(String(20))
    in_pantry_amount: Mapped[float] = mapped_column(Float)
    in_pantry_unit: Mapped[str] = mapped_column(String(20))
    to_buy_amount: Mapped[float] = mapped_column(Float)
    to_buy_unit: Mapped[str] = mapped_column(String(20))
    estimated_price: Mapped[Decimal | None] = mapped_column(MONEY)

    ingredient: Mapped[IngredientRow] = relationship(lazy="selectin")
