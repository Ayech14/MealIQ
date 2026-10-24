"""Enumerations from the Stage 1 domain class diagram."""

from enum import Enum


class WeightGoal(str, Enum):
    GAIN = "GAIN"
    LOSE = "LOSE"
    MAINTAIN = "MAINTAIN"


class MealType(str, Enum):
    BREAKFAST = "BREAKFAST"
    LUNCH = "LUNCH"
    DINNER = "DINNER"
    SNACK = "SNACK"


class PlanStatus(str, Enum):
    DRAFT = "DRAFT"
    ACTIVE = "ACTIVE"
    ARCHIVED = "ARCHIVED"


class RecipeSource(str, Enum):
    CURATED = "CURATED"
    EXTERNAL = "EXTERNAL"
    GENERATED = "GENERATED"


class PlanningMode(str, Enum):
    NUTRITION_GOAL = "NUTRITION_GOAL"
    BUDGET = "BUDGET"
    TIME_AWARE = "TIME_AWARE"


class ChangeType(str, Enum):
    REPLACE_MEAL = "REPLACE_MEAL"
    SUBSTITUTE_INGREDIENT = "SUBSTITUTE_INGREDIENT"
    UPDATE_CONSTRAINT = "UPDATE_CONSTRAINT"


class ChangeEventType(str, Enum):
    PANTRY_CHANGED = "PANTRY_CHANGED"
    PROFILE_CHANGED = "PROFILE_CHANGED"
    BUDGET_CHANGED = "BUDGET_CHANGED"
    TIME_LIMIT_CHANGED = "TIME_LIMIT_CHANGED"


class ResponseStatus(str, Enum):
    SUCCESS = "SUCCESS"
    NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION"
    NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION"
    CONFLICT = "CONFLICT"
    FAILED = "FAILED"


class DayOfWeek(str, Enum):
    """Day keys for per-day cooking-time limits; order matches date.weekday()."""

    MON = "MON"
    TUE = "TUE"
    WED = "WED"
    THU = "THU"
    FRI = "FRI"
    SAT = "SAT"
    SUN = "SUN"

    @classmethod
    def of(cls, day) -> "DayOfWeek":
        """Return the DayOfWeek of a date or datetime."""
        return list(cls)[day.weekday()]
