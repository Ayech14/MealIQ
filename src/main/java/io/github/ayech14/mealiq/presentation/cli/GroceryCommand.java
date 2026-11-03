package io.github.ayech14.mealiq.presentation.cli;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.GroceryList;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** {@code mealiq grocery generate|show}: the priced shopping list for the active plan (F07, F08, F12). */
@Command(name = "grocery", description = "Make or show the shopping list for your plan.",
        mixinStandardHelpOptions = true)
final class GroceryCommand implements Runnable {

    @ParentCommand
    MealPlanningCLI cli;

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(cli.out());
    }

    @Command(name = "generate", aliases = "optimize", description = {
        "Work out what to buy for the active plan after your pantry, price it and compare it with your budget."})
    int generate() {
        AgentResponse response = cli.facade().generateOptimizedGroceryList(cli.profileId());
        print(response.groceryList());
        BudgetStatus status = response.budgetStatus();
        if (status != null) {
            cli.out().println("Budget for this plan: $" + status.budget() + (status.withinBudget()
                    ? (status.partial() ? ". The priced items fit." : ". Within budget.")
                    : ". Over by $" + status.shortfall() + "."));
        } else if (!response.rationale().isEmpty()) {
            cli.out().println(response.rationale());
        }
        response.conflicts().forEach(c -> cli.out().println("Note: " + c));
        return MealPlanningCLI.OK;
    }

    @Command(name = "show", description = "Show the saved shopping list.")
    int show() {
        Optional<GroceryList> list = cli.facade().getGroceryList(cli.profileId());
        if (list.isEmpty()) {
            cli.out().println("No shopping list yet. Make one with: mealiq grocery generate");
            return MealPlanningCLI.FAILED;
        }
        if (list.get().isStale()) {
            cli.out().println("This list is out of date: your plan or pantry changed. Refresh it with: "
                    + "mealiq grocery generate");
        }
        print(list.get());
        return MealPlanningCLI.OK;
    }

    private void print(GroceryList list) {
        PrintWriter out = cli.out();
        out.printf("  %-26s %10s %10s %10s %8s%n", "Ingredient", "need", "have", "buy", "price");
        String category = null;
        for (GroceryItem item : list.items()) {
            if (!item.ingredient().category().equals(category)) {
                category = item.ingredient().category();
                out.println("  " + category);
            }
            String price = item.toBuy().isZero() ? "-"
                    : item.estimatedPrice() == null ? "?" : "$" + item.estimatedPrice();
            out.printf("    %-24s %10s %10s %10s %8s%n", item.ingredient().name(), item.required(),
                    item.inPantry().isZero() ? "-" : item.inPantry().toString(),
                    item.toBuy().isZero() ? "-" : item.toBuy().toString(), price);
        }
        List<GroceryItem> toBuy = list.items().stream().filter(i -> !i.toBuy().isZero()).toList();
        long unpriced = toBuy.stream().filter(i -> i.estimatedPrice() == null).count();
        out.println(toBuy.size() + " of " + list.items().size() + " ingredients to buy. Estimated cost: "
                + (unpriced == 0 ? "$" + list.total()
                        : unpriced == toBuy.size() ? "unknown (no prices available)"
                        : "at least $" + list.total() + " (" + unpriced + " item(s) without a price)"));
        out.println("Prices: " + cli.facade().priceSource() + ".");
    }
}
