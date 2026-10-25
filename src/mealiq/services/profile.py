"""ProfileService: validates and saves the user's goals and constraints (F01, UC01)."""

from __future__ import annotations

from dataclasses import fields
from decimal import Decimal
from uuid import UUID

from mealiq.domain import ChangeEventType, UserProfile
from mealiq.domain.diet import DIET_RULES, is_supported_restriction, normalize_tag
from mealiq.events import PlanRelevantChange, PlanRelevantChangePublisher
from mealiq.persistence.repositories import IngredientRepository, ProfileRepository
from mealiq.services.errors import InvalidInputError, NotFoundError

MAX_MEALS_PER_DAY = 6
MAX_MINUTES_PER_DAY = 600
CALORIE_TARGET_RANGE = (800, 6000)
PROTEIN_TARGET_RANGE = (0, 400)

# Which event a changed field raises; any other field change is PROFILE_CHANGED.
_FIELD_EVENTS = {
    "weekly_budget": ChangeEventType.BUDGET_CHANGED,
    "max_cook_minutes": ChangeEventType.TIME_LIMIT_CHANGED,
}


class ProfileService(PlanRelevantChangePublisher):
    def __init__(self, profile_repository: ProfileRepository, ingredient_repository: IngredientRepository) -> None:
        super().__init__()
        self._profiles = profile_repository
        self._ingredients = ingredient_repository

    def get_profile(self, profile_id: UUID) -> UserProfile:
        profile = self._profiles.find_by_id(profile_id)
        if profile is None:
            raise NotFoundError(f"Profile {profile_id} not found")
        return profile

    def update_profile(self, profile: UserProfile) -> UserProfile:
        """Validate, save, then publish one event per kind of change.

        Invalid values raise InvalidInputError and nothing is saved (UC01 3a).
        """
        errors = self.validate(profile)
        if errors:
            raise InvalidInputError(errors)
        previous = self._profiles.find_by_id(profile.profile_id)
        self._profiles.save(profile)
        if previous is not None:
            for event in self._events_for(previous, profile):
                self.publish(event)
        return profile

    def validate(self, profile: UserProfile) -> dict[str, str]:
        """Field-level messages for out-of-range values and contradictions."""
        errors: dict[str, str] = {}
        if not 1 <= profile.meals_per_day <= MAX_MEALS_PER_DAY:
            errors["meals_per_day"] = f"must be between 1 and {MAX_MEALS_PER_DAY}"
        if profile.weekly_budget < Decimal("0"):
            errors["weekly_budget"] = "cannot be negative"
        low, high = CALORIE_TARGET_RANGE
        if profile.daily_calorie_target is not None and not low <= profile.daily_calorie_target <= high:
            errors["daily_calorie_target"] = f"must be between {low} and {high} kcal"
        low, high = PROTEIN_TARGET_RANGE
        if profile.daily_protein_target_g is not None and not low <= profile.daily_protein_target_g <= high:
            errors["daily_protein_target_g"] = f"must be between {low} and {high} g"
        bad_days = [d.value for d, m in profile.max_cook_minutes.items() if not 0 <= m <= MAX_MINUTES_PER_DAY]
        if bad_days:
            errors["max_cook_minutes"] = f"{', '.join(bad_days)}: must be between 0 and {MAX_MINUTES_PER_DAY} minutes"
        unsupported = sorted(r for r in profile.dietary_restrictions if not is_supported_restriction(r))
        if unsupported:
            # A hard constraint we cannot enforce must not be silently accepted (D04).
            errors["dietary_restrictions"] = (
                f"unsupported: {', '.join(unsupported)}; supported: {', '.join(sorted(DIET_RULES))}"
            )
        both = sorted(_names(profile.preferred_foods) & _names(profile.disliked_foods))
        if both:
            errors["preferred_foods"] = f"also listed as disliked: {', '.join(both)}"
        return errors

    def preference_warnings(self, profile: UserProfile) -> list[str]:
        """Preferred foods that the profile's hard constraints forbid (UC01 3b).

        These are flagged, not rejected: the allergy or restriction always wins, and the
        user is asked to resolve the conflict.
        """
        warnings = []
        for name in sorted(profile.preferred_foods):
            ingredient = self._ingredients.find_by_name(name)
            if ingredient is not None and not profile.is_allowed(ingredient):
                warnings.append(f"Preferred food '{name}' conflicts with your allergies or diet and will be avoided.")
        return warnings

    def _events_for(self, before: UserProfile, after: UserProfile) -> list[PlanRelevantChange]:
        changed = [f.name for f in fields(UserProfile) if getattr(before, f.name) != getattr(after, f.name)]
        by_type: dict[ChangeEventType, list[str]] = {}
        for name in changed:
            by_type.setdefault(_FIELD_EVENTS.get(name, ChangeEventType.PROFILE_CHANGED), []).append(name)
        return [
            PlanRelevantChange(type=event_type, profile_id=after.profile_id, details={"changed_fields": names})
            for event_type, names in by_type.items()
        ]


def _names(values: set[str]) -> set[str]:
    return {normalize_tag(v) for v in values}
