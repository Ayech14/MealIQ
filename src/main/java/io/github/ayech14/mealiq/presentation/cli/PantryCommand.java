package io.github.ayech14.mealiq.presentation.cli;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** {@code mealiq pantry list|set|remove}: what the user already has at home (F02, UC02). */
@Command(name = "pantry", description = "List or change the ingredients you have at home.",
        mixinStandardHelpOptions = true)
final class PantryCommand implements Runnable {

    @ParentCommand
    MealPlanningCLI cli;

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(cli.out());
    }

    @Command(name = "list", description = "Show the pantry; expired items are marked and not counted as stock.")
    int list() {
        print(cli.facade().getPantry(cli.profileId()));
        return MealPlanningCLI.OK;
    }

    @Command(name = "set", description = {
        "Add an ingredient or replace its quantity. A quantity of 0 keeps it listed as out of stock.",
        "Example: mealiq pantry set \"soy milk\" 1 l --expires 2026-11-20"})
    int set(@Parameters(index = "0", paramLabel = "INGREDIENT", description = "Catalogue name (see: mealiq ingredients)")
            String ingredient,
            @Parameters(index = "1", paramLabel = "AMOUNT") double amount,
            @Parameters(index = "2", paramLabel = "UNIT", description = "e.g. g, kg, ml, l, cup, unit")
            String unit,
            @Option(names = "--expires", paramLabel = "YYYY-MM-DD") LocalDate expires) {
        UUID profileId = cli.profileId();
        PantryItem item = cli.facade().pantryItem(ingredient, amount, unit, expires);
        AgentResponse response = cli.facade().updatePantry(profileId, item);
        cli.out().println(response.rationale());
        cli.printImpactNotice();
        return MealPlanningCLI.OK;
    }

    @Command(name = "remove", description = "Remove an ingredient from the pantry.")
    int remove(@Parameters(paramLabel = "INGREDIENT") String ingredient) {
        cli.facade().removePantryItem(cli.profileId(), ingredient);
        cli.out().println("Removed " + ingredient.strip() + " from the pantry.");
        cli.printImpactNotice();
        return MealPlanningCLI.OK;
    }

    private void print(Pantry pantry) {
        PrintWriter out = cli.out();
        if (pantry.items().isEmpty()) {
            out.println("The pantry is empty. Add items with: mealiq pantry set INGREDIENT AMOUNT UNIT");
            return;
        }
        Set<UUID> expired = cli.facade().expiredPantryItems(cli.profileId()).stream()
                .map(i -> i.ingredient().id()).collect(Collectors.toSet());
        List<PantryItem> items = pantry.items().values().stream()
                .sorted(Comparator.comparing((PantryItem i) -> i.ingredient().name())).toList();
        out.println("Pantry (" + cli.profileName + "): " + items.size() + " item" + (items.size() == 1 ? "" : "s"));
        for (PantryItem item : items) {
            String note = item.expiryDate() == null ? ""
                    : "expires " + item.expiryDate() + (expired.contains(item.ingredient().id()) ? " (expired)" : "");
            out.println(String.format("  %-24s %-10s %s", item.ingredient().name(), item.quantity(), note)
                    .stripTrailing());
        }
    }
}
