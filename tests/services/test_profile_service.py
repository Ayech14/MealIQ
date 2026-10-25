from decimal import Decimal
from uuid import uuid4

import pytest

from mealiq.domain import ChangeEventType, DayOfWeek, UserProfile, WeightGoal
from mealiq.services.errors import InvalidInputError, NotFoundError
from mealiq.services.profile import ProfileService


@pytest.fixture
def repo(profile_repo):
    return profile_repo


@pytest.fixture
def service(repo, ingredients, observer):
    service = ProfileService(repo, ingredients)
    service.subscribe(observer)
    return service


def valid_profile(**overrides):
    values = dict(goal=WeightGoal.GAIN, weekly_budget=Decimal("100"), meals_per_day=3,
                  max_cook_minutes={DayOfWeek.MON: 30})
    values.update(overrides)
    return UserProfile(**values)


def test_save_and_get(service, repo):
    profile = service.update_profile(valid_profile())
    assert service.get_profile(profile.profile_id) == profile
    assert profile.profile_id in repo.rows


def test_get_unknown_profile(service):
    with pytest.raises(NotFoundError):
        service.get_profile(uuid4())


@pytest.mark.parametrize(
    ("overrides", "field"),
    [
        ({"meals_per_day": 0}, "meals_per_day"),
        ({"meals_per_day": 7}, "meals_per_day"),
        ({"weekly_budget": Decimal("-1")}, "weekly_budget"),
        ({"daily_calorie_target": 300}, "daily_calorie_target"),
        ({"daily_protein_target_g": 900}, "daily_protein_target_g"),
        ({"max_cook_minutes": {DayOfWeek.TUE: -5}}, "max_cook_minutes"),
        ({"dietary_restrictions": {"keto"}}, "dietary_restrictions"),
        ({"preferred_foods": {"Salmon"}, "disliked_foods": {"salmon"}}, "preferred_foods"),
    ],
)
def test_invalid_values_are_rejected_and_not_saved(service, repo, overrides, field):
    with pytest.raises(InvalidInputError) as caught:
        service.update_profile(valid_profile(**overrides))
    assert field in caught.value.errors
    assert repo.rows == {}


def test_all_field_errors_are_reported_together(service):
    with pytest.raises(InvalidInputError) as caught:
        service.update_profile(valid_profile(meals_per_day=0, weekly_budget=Decimal("-5")))
    assert set(caught.value.errors) == {"meals_per_day", "weekly_budget"}


def test_preferred_food_with_allergen_is_flagged_not_rejected(service):
    profile = valid_profile(allergies={"peanut"}, preferred_foods={"peanut butter", "banana"})
    service.update_profile(profile)
    warnings = service.preference_warnings(profile)
    assert len(warnings) == 1 and "peanut butter" in warnings[0]


def test_preferred_food_against_diet_is_flagged(service):
    profile = valid_profile(dietary_restrictions={"vegetarian"}, preferred_foods={"salmon"})
    assert service.preference_warnings(profile)


class TestEvents:
    def test_first_save_publishes_nothing(self, service, observer):
        service.update_profile(valid_profile())
        assert observer.events == []

    def test_unchanged_save_publishes_nothing(self, service, observer):
        profile = service.update_profile(valid_profile())
        service.update_profile(profile)
        assert observer.events == []

    def test_each_kind_of_change_gets_its_event(self, service, observer):
        profile = service.update_profile(valid_profile())
        profile.weekly_budget = Decimal("80")
        profile.max_cook_minutes = {DayOfWeek.MON: 15}
        profile.allergies = {"peanut"}
        service.update_profile(profile)

        by_type = {e.type: e for e in observer.events}
        assert set(by_type) == {
            ChangeEventType.BUDGET_CHANGED,
            ChangeEventType.TIME_LIMIT_CHANGED,
            ChangeEventType.PROFILE_CHANGED,
        }
        assert by_type[ChangeEventType.PROFILE_CHANGED].details == {"changed_fields": ["allergies"]}
        assert all(e.profile_id == profile.profile_id for e in observer.events)

    def test_rejected_update_publishes_nothing(self, service, observer):
        profile = service.update_profile(valid_profile())
        profile.meals_per_day = 0
        with pytest.raises(InvalidInputError):
            service.update_profile(profile)
        assert observer.events == []
