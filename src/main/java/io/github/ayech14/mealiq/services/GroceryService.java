package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Unit;
import io.github.ayech14.mealiq.integration.PriceDataSource;
import io.github.ayech14.mealiq.persistence.GroceryListRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Turns a meal plan into a priced shopping list (F07, F08, SD02): what every meal needs,
 * minus usable pantry stock, with one line per ingredient. Units are combined only when the conversion
 * is exact or the catalogue gives the weights; otherwise the lines stay separate.
 */
public class GroceryService {

    private final GroceryListRepository groceryListRepository;
    private final PantryService pantryService;
    private final PriceDataSource priceSource;

    public GroceryService(GroceryListRepository groceryListRepository, PantryService pantryService,
                          PriceDataSource priceSource) {
        this.groceryListRepository = groceryListRepository;
        this.pantryService = pantryService;
        this.priceSource = priceSource;
    }

    /**
     * The list for a plan against a pantry, without saving it. Pantry stock is counted once
     * and only if it is still usable on the plan's first day.
     */
    public GroceryList generateList(MealPlan plan, Pantry pantry) {
        List<IngredientRequirement> needs = new ArrayList<>();
        for (PlannedMeal meal : plan.meals()) {
            double scale = (double) meal.servings() / meal.recipe().servings();
            for (IngredientRequirement line : meal.recipe().ingredients()) {
                if (!line.optional()) {
                    needs.add(new IngredientRequirement(line.ingredient(),
                            new Quantity(line.quantity().amount() * scale, line.quantity().unit()), false));
                }
            }
        }
        Pantry stock = pantry.copy();
        List<GroceryItem> items = new ArrayList<>();
        for (IngredientRequirement need : consolidate(needs)) {
            Quantity toBuy = stock.take(need, plan.startDate());
            Quantity inPantry = new Quantity(need.quantity().amount() - toBuy.amount(), need.quantity().unit());
            Unit shown = need.quantity().simplified().unit(); // all three amounts in one readable unit
            items.add(new GroceryItem(need.ingredient(), need.quantity().convertTo(shown), inPantry.convertTo(shown),
                    toBuy.convertTo(shown), null));
        }
        items.sort(Comparator.comparing((GroceryItem i) -> i.ingredient().category())
                .thenComparing(i -> i.ingredient().name()));
        return new GroceryList(plan.id(), items);
    }

    /**
     * The list with a price for every item to buy (SD02): items already covered by the pantry
     * cost nothing, and an item without a known price keeps a null price, which makes the
     * list partial. The estimated cost is the sum of the known prices.
     */
    public GroceryList optimizeList(GroceryList list) {
        List<GroceryItem> priced = list.items().stream().map(item -> new GroceryItem(item.ingredient(),
                item.required(), item.inPantry(), item.toBuy(), item.toBuy().isZero() ? BigDecimal.ZERO
                        : priceSource.estimatePrice(item.ingredient(), item.toBuy())
                                .map(p -> p.setScale(2, RoundingMode.HALF_UP)).orElse(null))).toList();
        GroceryList result = new GroceryList(list.id(), list.planId(), priced, BigDecimal.ZERO, false);
        result.estimatedCost(result.total());
        return result;
    }

    /** Generates and prices the list for a plan against the current pantry, and saves it. */
    public GroceryList refreshForPlan(MealPlan plan) {
        GroceryList list = optimizeList(generateList(plan, pantryService.getPantry(plan.profileId())));
        groceryListRepository.save(list);
        return list;
    }

    /** Where prices come from, for display next to estimates. */
    public String priceSource() {
        return priceSource.describe();
    }

    public Optional<GroceryList> getList(UUID planId) {
        return groceryListRepository.findByPlanId(planId);
    }

    /**
     * One requirement per ingredient where the amounts can be added safely: same dimension by
     * unit conversion, otherwise by weight from the catalogue. Amounts that cannot be
     * combined stay as separate requirements; nothing is converted unsafely.
     */
    List<IngredientRequirement> consolidate(List<IngredientRequirement> needs) {
        Map<UUID, List<Quantity>> byIngredient = new LinkedHashMap<>();
        Map<UUID, Ingredient> ingredients = new LinkedHashMap<>();
        for (IngredientRequirement need : needs) {
            ingredients.putIfAbsent(need.ingredient().id(), need.ingredient());
            byIngredient.computeIfAbsent(need.ingredient().id(), id -> new ArrayList<>()).add(need.quantity());
        }
        List<IngredientRequirement> merged = new ArrayList<>();
        byIngredient.forEach((id, quantities) -> {
            Ingredient ingredient = ingredients.get(id);
            for (Quantity total : combine(ingredient, quantities)) {
                merged.add(new IngredientRequirement(ingredient, total, false));
            }
        });
        return merged;
    }

    private static List<Quantity> combine(Ingredient ingredient, List<Quantity> quantities) {
        Unit first = quantities.getFirst().unit();
        if (quantities.stream().allMatch(q -> q.dimension() == first.dimension())) {
            return List.of(quantities.stream().reduce(new Quantity(0, first), Quantity::add));
        }
        double grams = 0;
        for (Quantity quantity : quantities) {
            OptionalDouble weight = ingredient.toGrams(quantity);
            if (weight.isEmpty()) {
                return combineByDimension(quantities);
            }
            grams += weight.getAsDouble();
        }
        return List.of(new Quantity(grams, Unit.G));
    }

    /** Fallback when weights are missing: one total per dimension. */
    private static List<Quantity> combineByDimension(List<Quantity> quantities) {
        Map<Unit.Dimension, Quantity> totals = new LinkedHashMap<>();
        for (Quantity quantity : quantities) {
            totals.merge(quantity.dimension(), quantity, Quantity::add);
        }
        return List.copyOf(totals.values());
    }
}
