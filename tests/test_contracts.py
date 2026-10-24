from datetime import date
from uuid import uuid4

import pytest
from pydantic import ValidationError

from mealiq.contracts import PlanChange, PlanImpact, PlanningContext, PlanRequest, RecipeQuery, ValidationResult
from mealiq.domain import ChangeType, MealPlan, Pantry, UserProfile


def test_plan_request_bounds_days():
    PlanRequest(profile_id=uuid4(), start_date=date(2026, 10, 26), days=7)
    with pytest.raises(ValidationError):
        PlanRequest(profile_id=uuid4(), start_date=date(2026, 10, 26), days=0)


def test_recipe_query_rejects_non_positive_time():
    with pytest.raises(ValidationError):
        RecipeQuery(max_minutes=0)


def test_context_keeps_live_domain_objects():
    profile = UserProfile()
    pantry = Pantry(profile_id=profile.profile_id)
    plan = MealPlan(profile_id=profile.profile_id, start_date=date(2026, 10, 26), days=7)
    request = PlanRequest(profile_id=profile.profile_id, start_date=plan.start_date)
    context = PlanningContext(request=request, profile=profile, pantry=pantry, active_plan=plan)
    assert context.profile is profile
    assert context.active_plan is plan


def test_plan_change_needs_proposal_or_question():
    with pytest.raises(ValidationError):
        PlanChange(change_type=ChangeType.REPLACE_MEAL)
    change = PlanChange(change_type=ChangeType.REPLACE_MEAL, clarification_question="Which dinner?")
    assert change.needs_clarification


def test_validation_result_merge():
    merged = ValidationResult.ok(["partial prices"]).merge(ValidationResult.failed("contains peanut"))
    assert not merged.valid
    assert merged.violations == ["contains peanut"]
    assert merged.warnings == ["partial prices"]


def test_empty_impact():
    assert PlanImpact().is_empty()
    assert not PlanImpact(conflicts=["budget exceeded"]).is_empty()
