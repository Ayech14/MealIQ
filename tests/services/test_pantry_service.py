from datetime import date
from uuid import uuid4

import pytest

from mealiq.domain import ChangeEventType, Ingredient, PantryItem, Quantity
from mealiq.services.errors import InvalidInputError, NotFoundError
from mealiq.services.pantry import PantryService

TODAY = date(2026, 10, 25)


@pytest.fixture
def service(pantry_repo, ingredients, observer):
    service = PantryService(pantry_repo, ingredients, today=lambda: TODAY)
    service.subscribe(observer)
    return service


@pytest.fixture
def profile_id():
    return uuid4()


def test_add_list_and_update(service, profile_id):
    service.update_item(profile_id, service.make_item("Egg", 12, "unit"))
    service.update_item(profile_id, service.make_item("white rice", 1, "kg"))
    service.update_item(profile_id, service.make_item("egg", 6, "pieces"))

    pantry = service.get_pantry(profile_id)
    egg = service.make_item("egg", 0, "unit").ingredient
    assert len(pantry.items) == 2
    assert pantry.available_quantity(egg, TODAY) == Quantity(6, "unit")


def test_setting_zero_keeps_item_listed(service, profile_id):
    item = service.make_item("egg", 12, "unit")
    service.update_item(profile_id, item)
    service.update_item(profile_id, service.make_item("egg", 0, "unit"))
    pantry = service.get_pantry(profile_id)
    assert pantry.available_quantity(item.ingredient, TODAY).amount == 0
    assert item.ingredient.ingredient_id in pantry.items


def test_remove(service, profile_id):
    item = service.make_item("spinach", 200, "g")
    service.update_item(profile_id, item)
    service.remove_item(profile_id, item.ingredient.ingredient_id)
    assert service.get_pantry(profile_id).items == {}
    with pytest.raises(NotFoundError):
        service.remove_item(profile_id, item.ingredient.ingredient_id)


@pytest.mark.parametrize(
    ("name", "amount", "unit", "fields"),
    [
        ("egg", -1, "unit", {"quantity"}),
        ("egg", 2, "bunch", {"unit"}),
        ("dragon fruit", 1, "unit", {"ingredient"}),
        ("dragon fruit", -3, "handful", {"ingredient", "quantity", "unit"}),
    ],
)
def test_bad_input_is_rejected_per_field(service, name, amount, unit, fields):
    with pytest.raises(InvalidInputError) as caught:
        service.make_item(name, amount, unit)
    assert set(caught.value.errors) == fields


def test_ingredient_outside_catalogue_is_rejected(service, profile_id, observer):
    item = PantryItem(Ingredient("homemade sauce", "condiment"), Quantity(1, "cup"))
    with pytest.raises(InvalidInputError):
        service.update_item(profile_id, item)
    assert service.get_pantry(profile_id).items == {}
    assert observer.events == []


def test_expired_items_stay_listed_but_are_not_available(service, profile_id):
    milk = service.make_item("milk", 1, "l", expiry_date=date(2026, 10, 20))
    service.update_item(profile_id, milk)
    assert service.expired_items(profile_id) == [milk]
    assert service.get_pantry(profile_id).available_quantity(milk.ingredient, TODAY).amount == 0


class TestEvents:
    def test_update_publishes_pantry_changed_with_details(self, service, profile_id, observer):
        service.update_item(profile_id, service.make_item("egg", 12, "unit"))
        service.update_item(profile_id, service.make_item("egg", 4, "unit"))
        first, second = observer.events
        assert first.type is ChangeEventType.PANTRY_CHANGED
        assert first.profile_id == profile_id
        assert first.details["previous_quantity"] is None
        assert (second.details["previous_quantity"], second.details["quantity"]) == ("12 unit", "4 unit")

    def test_remove_publishes(self, service, profile_id, observer):
        item = service.make_item("egg", 12, "unit")
        service.update_item(profile_id, item)
        service.remove_item(profile_id, item.ingredient.ingredient_id)
        assert observer.events[-1].details["action"] == "removed"
        assert observer.events[-1].details["quantity"] is None

    def test_unsubscribed_observer_is_not_notified(self, service, profile_id, observer):
        service.unsubscribe(observer)
        service.update_item(profile_id, service.make_item("egg", 1, "unit"))
        assert observer.events == []

    def test_failing_observer_does_not_block_others_or_the_update(self, service, profile_id, observer):
        class Broken:
            def on_plan_relevant_change(self, event):
                raise RuntimeError("boom")

        service.unsubscribe(observer)
        service.subscribe(Broken())
        service.subscribe(observer)
        pantry = service.update_item(profile_id, service.make_item("egg", 1, "unit"))
        assert len(pantry.items) == 1
        assert len(observer.events) == 1
