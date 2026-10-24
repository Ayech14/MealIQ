"""Agent request, context, proposal, change, impact, validation and response types.

These are the Stage 1 "contracts" package. They are Pydantic models so field ranges
are validated at the boundary. Fields that hold domain entities keep the same object
(no copy), so a context or proposal always refers to the live domain state.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, model_validator

from mealiq.domain import (
    BudgetStatus,
    ChangeType,
    GroceryItem,
    GroceryList,
    MealPlan,
    MealType,
    Pantry,
    PlannedMeal,
    PlanningMode,
    Recipe,
    ResponseStatus,
    UserProfile,
)


class _Contract(BaseModel):
    model_config = ConfigDict(arbitrary_types_allowed=True)


class PlanRequest(_Contract):
    profile_id: UUID
    start_date: date
    days: int = Field(default=7, ge=1, le=14)
    mode: PlanningMode = PlanningMode.NUTRITION_GOAL
    instruction: str | None = None


class RecipeQuery(_Contract):
    meal_type: MealType | None = None
    max_minutes: int | None = Field(default=None, gt=0)
    include_ingredients: list[str] = Field(default_factory=list)
    exclude_ingredients: list[str] = Field(default_factory=list)
    max_cost_per_serving: Decimal | None = Field(default=None, ge=0)
    allow_generated: bool = False


class PlanningContext(_Contract):
    request: PlanRequest
    profile: UserProfile
    pantry: Pantry
    active_plan: MealPlan | None = None
    budget_status: BudgetStatus | None = None
    prior_decisions: list[str] = Field(default_factory=list)
    violations: list[str] = Field(default_factory=list)


class PlanningProposal(_Contract):
    meals: list[PlannedMeal]
    mode: PlanningMode
    rationale: str
    conflicts: list[str] = Field(default_factory=list)


class PlanChange(_Contract):
    """A proposed change to a plan. It never mutates the plan itself (D03).

    A change either carries a proposal to preview, or a clarification question when
    the request was ambiguous (for example, which meal to replace).
    """

    change_id: UUID = Field(default_factory=uuid4)
    change_type: ChangeType
    target_meal_ids: list[UUID] = Field(default_factory=list)
    proposal: PlanningProposal | None = None
    rationale: str = ""
    clarification_question: str | None = None

    @model_validator(mode="after")
    def _proposal_or_question(self) -> PlanChange:
        if self.proposal is None and not self.clarification_question:
            raise ValueError("A PlanChange needs either a proposal or a clarification question")
        return self

    @property
    def needs_clarification(self) -> bool:
        return self.proposal is None


class PlanImpact(_Contract):
    affected_meals: list[PlannedMeal] = Field(default_factory=list)
    affected_items: list[GroceryItem] = Field(default_factory=list)
    conflicts: list[str] = Field(default_factory=list)

    def is_empty(self) -> bool:
        return not (self.affected_meals or self.affected_items or self.conflicts)


class ValidationResult(_Contract):
    valid: bool
    violations: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)

    @classmethod
    def ok(cls, warnings: list[str] | None = None) -> ValidationResult:
        return cls(valid=True, warnings=warnings or [])

    @classmethod
    def failed(cls, *violations: str) -> ValidationResult:
        return cls(valid=False, violations=list(violations))

    def merge(self, other: ValidationResult) -> ValidationResult:
        """Combine two results; the merge is valid only if both are."""
        return ValidationResult(
            valid=self.valid and other.valid,
            violations=self.violations + other.violations,
            warnings=self.warnings + other.warnings,
        )


class AgentResponse(_Contract):
    status: ResponseStatus
    plan: MealPlan | None = None
    change: PlanChange | None = None
    grocery_list: GroceryList | None = None
    budget_status: BudgetStatus | None = None
    recipes: list[Recipe] = Field(default_factory=list)
    rationale: str = ""
    conflicts: list[str] = Field(default_factory=list)
