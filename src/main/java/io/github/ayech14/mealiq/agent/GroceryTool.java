package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.services.GroceryService;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code groceryList}: what the active plan still needs to be bought after the pantry (F07).
 * It only projects the list; saving it is the grocery use case's job.
 */
public class GroceryTool implements AgentTool {

    public static final String NAME = "groceryList";
    static final int MAX_LISTED = 20;

    private final GroceryService groceryService;

    public GroceryTool(GroceryService groceryService) {
        this.groceryService = groceryService;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String description() {
        return "what the active meal plan still needs to be bought after the pantry, with prices where known. "
                + "No arguments";
    }

    @Override
    public List<String> checkArguments(Map<String, Object> args) {
        return new ToolArguments(args, Set.of()).problems();
    }

    public GroceryList project(MealPlan plan, Pantry pantry) {
        return groceryService.optimizeList(groceryService.generateList(plan, pantry));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, PlanningContext context) {
        if (context.activePlan() == null) {
            return ToolResult.failed(NAME, "there is no active meal plan yet");
        }
        GroceryList list = project(context.activePlan(), context.pantry());
        List<GroceryItem> toBuy = list.items().stream().filter(i -> !i.toBuy().isZero()).toList();
        StringBuilder summary = new StringBuilder(toBuy.size() + " item(s) to buy");
        summary.append(list.isPartial() ? " (some prices unknown):" : ", estimated $" + list.total() + ":");
        toBuy.stream().limit(MAX_LISTED).forEach(i -> summary.append("\n- ").append(i.ingredient().name())
                .append(' ').append(i.toBuy()).append(i.estimatedPrice() == null ? "" : " $" + i.estimatedPrice()));
        if (toBuy.size() > MAX_LISTED) {
            summary.append("\n- ... and ").append(toBuy.size() - MAX_LISTED).append(" more");
        }
        return ToolResult.ok(NAME, list, summary.toString());
    }
}
