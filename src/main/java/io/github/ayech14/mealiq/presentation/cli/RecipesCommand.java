package io.github.ayech14.mealiq.presentation.cli;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** {@code mealiq recipes list|show}: browse the recipe collection (F03 deterministic search, F06 nutrition). */
@Command(name = "recipes", description = "Browse recipes that fit your profile.", mixinStandardHelpOptions = true)
final class RecipesCommand implements Runnable {

    /** CLI names for the ranking modes that have the data they need. */
    enum Rank {
        NUTRITION(PlanningMode.NUTRITION_GOAL), TIME(PlanningMode.TIME_AWARE);

        final PlanningMode mode;

        Rank(PlanningMode mode) {
            this.mode = mode;
        }
    }

    @ParentCommand
    MealPlanningCLI cli;

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(cli.out());
    }

    @Command(name = "list", description = {
        "Recipes that fit your allergies and diet, best match first.",
        "Example: mealiq recipes list --meal dinner --max-minutes 30 --include chicken"})
    int list(@Option(names = "--meal", paramLabel = "TYPE", description = "breakfast, lunch, dinner or snack")
             MealType meal,
             @Option(names = "--max-minutes", paramLabel = "N") Integer maxMinutes,
             @Option(names = "--include", paramLabel = "INGREDIENTS", split = ",",
                     description = "Only recipes using at least one of these") List<String> include,
             @Option(names = "--exclude", paramLabel = "INGREDIENTS", split = ",",
                     description = "Leave out recipes using any of these") List<String> exclude,
             @Option(names = "--rank", paramLabel = "BY", defaultValue = "nutrition",
                     description = "nutrition (your calorie and protein targets) or time (default: ${DEFAULT-VALUE})")
             Rank rank,
             @Option(names = "--limit", paramLabel = "N", defaultValue = "10") int limit) {
        RecipeQuery query = new RecipeQuery(meal, maxMinutes, include, exclude, null, false);
        AgentResponse response = cli.facade().browseRecipes(cli.profileId(), query, rank.mode);
        PrintWriter out = cli.out();
        if (!response.rationale().isEmpty()) {
            out.println(response.rationale());
        }
        List<Recipe> shown = response.recipes().stream().limit(Math.max(limit, 0)).toList();
        if (shown.isEmpty()) {
            out.println("No recipe fits these filters and your restrictions.");
        } else {
            OptionalInt timeLimit = cli.facade().cookingLimitToday(cli.profileId());
            out.printf("  %-40s %6s %7s %9s%n", "Recipe (ranked by " + rank.name().toLowerCase(Locale.ROOT) + ")",
                    "min", "kcal", "protein");
            boolean anyOver = false;
            for (Recipe recipe : shown) {
                NutritionSummary serving = cli.facade().recipeNutrition(recipe, 1);
                boolean over = timeLimit.isPresent() && recipe.totalMinutes() > timeLimit.getAsInt();
                anyOver |= over;
                out.printf("  %-40s %6s %7s %9s%n", recipe.name(), recipe.totalMinutes() + (over ? "*" : ""),
                        serving.complete() ? String.valueOf(Math.round(serving.calories())) : "?",
                        serving.complete() ? Math.round(serving.proteinG()) + " g" : "?");
            }
            out.println("Per serving. " + response.recipes().size() + " match"
                    + (response.recipes().size() == 1 ? "" : "es") + "; details: mealiq recipes show \"NAME\"");
            if (anyOver) {
                out.println("* Over today's cooking-time limit of " + timeLimit.getAsInt() + " minutes.");
            }
        }
        if (!response.conflicts().isEmpty()) {
            out.println("Left out because of your allergies or diet:");
            response.conflicts().forEach(c -> out.println("  " + c));
        }
        return MealPlanningCLI.OK;
    }

    @Command(name = "show", description = "Ingredients, steps and nutrition of one recipe.")
    int show(@Parameters(paramLabel = "NAME") String name,
             @Option(names = "--servings", paramLabel = "N", defaultValue = "1",
                     description = "Servings for the nutrition total (default: ${DEFAULT-VALUE})") int servings) {
        Recipe recipe = cli.facade().findRecipe(name);
        PrintWriter out = cli.out();
        out.println(recipe.name());
        out.println("  " + String.join(", ", recipe.mealTypes().stream().sorted()
                .map(t -> t.name().toLowerCase(Locale.ROOT)).toList())
                + " | " + recipe.prepMinutes() + " min prep + " + recipe.cookMinutes() + " min cooking"
                + " | makes " + recipe.servings() + " serving" + (recipe.servings() == 1 ? "" : "s"));
        if (!recipe.tags().isEmpty()) {
            out.println("  Tags: " + String.join(", ", recipe.tags().stream().sorted().toList()));
        }
        out.println("Ingredients:");
        for (IngredientRequirement line : recipe.ingredients()) {
            out.println("  " + line.quantity() + " " + line.ingredient().name() + (line.optional() ? " (optional)" : ""));
        }
        out.println("Steps:");
        for (int i = 0; i < recipe.instructions().size(); i++) {
            out.println("  " + (i + 1) + ". " + recipe.instructions().get(i));
        }
        NutritionSummary total = cli.facade().recipeNutrition(recipe, servings);
        out.printf("Nutrition for %d serving%s: %s%n", servings, servings == 1 ? "" : "s", total.complete()
                ? String.format("%d kcal, %.0f g protein, %.0f g carbs, %.0f g fat",
                        Math.round(total.calories()), total.proteinG(), total.carbsG(), total.fatG())
                : "incomplete (an ingredient has no catalogue data)");
        ValidationResult check = cli.facade().checkRecipe(cli.profileId(), recipe);
        check.violations().forEach(v -> out.println("Not suitable: " + v));
        check.warnings().forEach(w -> out.println("Note: " + w));
        out.println("Values come from USDA FoodData Central. A planning aid, not dietary advice.");
        return MealPlanningCLI.OK;
    }
}
