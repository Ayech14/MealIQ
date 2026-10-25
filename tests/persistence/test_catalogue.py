import pytest
from sqlalchemy import func, select

from mealiq.domain import IngredientRequirement, MealType, Quantity, Recipe, UserProfile
from mealiq.persistence.catalogue import ingredient_id_for, load_catalogue, read_catalogue
from mealiq.persistence.sql import SqlIngredientRepository
from mealiq.persistence.sql.tables import IngredientRow
from mealiq.services.nutrition import NutritionService


@pytest.fixture
def loaded(session_factory):
    repository = SqlIngredientRepository(session_factory)
    count = load_catalogue(repository)
    return repository, count


def test_packaged_catalogue_is_complete():
    entries = read_catalogue()
    assert len(entries) >= 140
    names = {i.name for i, _ in entries}
    assert len(names) == len(entries)
    for ingredient, substitutes in entries:
        assert ingredient.fdc_id, ingredient.name
        assert ingredient.nutrition_per_100g and ingredient.nutrition_per_100g.complete, ingredient.name
        assert set(substitutes) <= names, ingredient.name


def test_load_saves_every_ingredient_with_stable_ids(loaded):
    repository, count = loaded
    egg = repository.find_by_name("egg")
    assert egg.ingredient_id == ingredient_id_for("egg")
    assert egg.fdc_id == 171287
    assert egg.grams_per_unit == 50
    assert egg.allergens == {"egg"}
    assert count == len(read_catalogue())


def test_reloading_updates_instead_of_duplicating(loaded, session_factory):
    repository, count = loaded
    load_catalogue(repository)
    with session_factory() as session:
        assert session.scalar(select(func.count()).select_from(IngredientRow)) == count


def test_substitutes_are_linked(loaded):
    repository, _ = loaded
    milk = repository.find_by_name("milk")
    assert {s.name for s in repository.find_substitutes(milk)} == {"soy milk", "almond milk", "skim milk"}


def test_diet_rules_apply_to_catalogue_categories(loaded):
    repository, _ = loaded
    vegan = UserProfile(dietary_restrictions={"vegan"})
    vegetarian = UserProfile(dietary_restrictions={"vegetarian"})
    gluten_free = UserProfile(dietary_restrictions={"gluten-free"})
    assert not vegan.is_allowed(repository.find_by_name("honey"))
    assert not vegan.is_allowed(repository.find_by_name("mayonnaise"))
    assert vegan.is_allowed(repository.find_by_name("tofu"))
    assert not vegetarian.is_allowed(repository.find_by_name("chicken broth"))
    assert vegetarian.is_allowed(repository.find_by_name("vegetable broth"))
    assert not gluten_free.is_allowed(repository.find_by_name("soy sauce"))
    assert gluten_free.is_allowed(repository.find_by_name("white rice"))


def test_nutrition_from_real_catalogue_values(loaded):
    """Two large eggs and a cup of dry white rice, using USDA SR Legacy values."""
    repository, _ = loaded
    egg, rice = repository.find_by_name("egg"), repository.find_by_name("white rice")
    recipe = Recipe(
        "egg rice", {MealType.LUNCH}, prep_minutes=5, cook_minutes=20, servings=1,
        ingredients=[
            IngredientRequirement(egg, Quantity(2, "unit")),
            IngredientRequirement(rice, Quantity(1, "cup")),
        ],
    )
    summary = NutritionService().calculate_nutrition(recipe, 1)
    # egg: 100 g * 143 kcal/100 g; rice: 185 g (USDA cup weight) * 365 kcal/100 g
    assert summary.calories == pytest.approx(143 + 1.85 * 365, rel=1e-3)
    assert summary.complete
