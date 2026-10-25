"""PantryService: maintains the user's pantry inventory (F02, UC02)."""

from __future__ import annotations

from collections.abc import Callable
from datetime import date
from uuid import UUID

from mealiq.domain import ChangeEventType, Pantry, PantryItem, Quantity, UnitError
from mealiq.events import PlanRelevantChange, PlanRelevantChangePublisher
from mealiq.persistence.repositories import IngredientRepository, PantryRepository
from mealiq.services.errors import InvalidInputError, NotFoundError


class PantryService(PlanRelevantChangePublisher):
    def __init__(
        self,
        pantry_repository: PantryRepository,
        ingredient_repository: IngredientRepository,
        today: Callable[[], date] = date.today,
    ) -> None:
        super().__init__()
        self._pantries = pantry_repository
        self._ingredients = ingredient_repository
        self._today = today

    def get_pantry(self, profile_id: UUID) -> Pantry:
        return self._pantries.find_by_profile_id(profile_id)

    def make_item(self, ingredient_name: str, amount: float, unit: str, expiry_date: date | None = None) -> PantryItem:
        """Build a PantryItem from interface input, rejecting bad fields (UC02 3a)."""
        errors: dict[str, str] = {}
        ingredient = self._ingredients.find_by_name(ingredient_name)
        if ingredient is None:
            errors["ingredient"] = f"'{ingredient_name}' is not in the ingredient catalogue"
        if amount < 0:
            errors["quantity"] = "cannot be negative"
        try:
            Quantity(0, unit)
        except UnitError:
            errors["unit"] = f"unknown unit '{unit}'"
        if errors:
            raise InvalidInputError(errors)
        return PantryItem(ingredient, Quantity(amount, unit), expiry_date)

    def update_item(self, profile_id: UUID, item: PantryItem) -> Pantry:
        """Add or replace one item, save, and publish PANTRY_CHANGED.

        A quantity of zero keeps the item listed as out of stock; use remove_item to
        delete it.
        """
        if self._ingredients.find_by_id(item.ingredient.ingredient_id) is None:
            raise InvalidInputError({"ingredient": f"'{item.ingredient.name}' is not in the ingredient catalogue"})
        pantry = self._pantries.find_by_profile_id(profile_id)
        previous = pantry.items.get(item.ingredient.ingredient_id)
        pantry.update_item(item)
        self._pantries.save(pantry)
        self.publish(self._event(profile_id, "updated", item, previous))
        return pantry

    def remove_item(self, profile_id: UUID, ingredient_id: UUID) -> Pantry:
        pantry = self._pantries.find_by_profile_id(profile_id)
        previous = pantry.items.get(ingredient_id)
        if previous is None:
            raise NotFoundError("That ingredient is not in the pantry")
        pantry.remove_item(ingredient_id)
        self._pantries.save(pantry)
        self.publish(self._event(profile_id, "removed", None, previous))
        return pantry

    def expired_items(self, profile_id: UUID) -> list[PantryItem]:
        """Items kept in the list but excluded from available stock (UC02 3b)."""
        today = self._today()
        return [i for i in self.get_pantry(profile_id).items.values() if i.is_expired(today)]

    def _event(
        self, profile_id: UUID, action: str, item: PantryItem | None, previous: PantryItem | None
    ) -> PlanRelevantChange:
        subject = item or previous
        return PlanRelevantChange(
            type=ChangeEventType.PANTRY_CHANGED,
            profile_id=profile_id,
            details={
                "action": action,
                "ingredient_id": subject.ingredient.ingredient_id,
                "ingredient": subject.ingredient.name,
                "previous_quantity": str(previous.quantity) if previous else None,
                "quantity": str(item.quantity) if item else None,
            },
        )
