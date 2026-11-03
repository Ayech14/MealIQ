package io.github.ayech14.mealiq.presentation.cli;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
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

/**
 * {@code mealiq recipes list|find|generate|show}: browse recipes, ask the agent (F03), have it
 * write one (F04), and see nutrition (F06).
 */
@Command(name = "recipes", description = "Browse recipes that fit your profile.", mixinStandardHelpOptions = true)
final class RecipesCommand implements Runnable {

    /** CLI names for the ranking modes. */
    enum Rank {
        NUTRITION(PlanningMode.NUTRITION_GOAL), TIME(PlanningMode.TIME_AWARE), BUDGET(PlanningMode.BUDGET);

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
                     description = "nutrition (your calorie and protein targets), time or budget "
                             + "(default: ${DEFAULT-VALUE})")
             Rank rank,
             @Option(names = "--limit", paramLabel = "N", defaultValue = "10") int limit,
             @Option(names = "--generated", description = "Also list saved AI-generated recipes") boolean generated) {
        RecipeQuery query = new RecipeQuery(meal, maxMinutes, include, exclude, null, generated);
        AgentResponse response = cli.facade().browseRecipes(cli.profileId(), query, rank.mode);
        PrintWriter out = cli.out();
        if (!response.rationale().isEmpty()) {
            out.println(response.rationale());
        }
        List<Recipe> shown = response.recipes().stream().limit(Math.max(limit, 0)).toList();
        if (shown.isEmpty()) {
            out.println("No recipe fits these filters and your restrictions.");
        } else {
            printTable(shown, "Recipe (ranked by " + rank.name().toLowerCase(Locale.ROOT) + ")");
            out.println("Per serving; cost is what you would buy beyond your pantry (+ means some prices are "
                    + "unknown). " + response.recipes().size() + " match" + (response.recipes().size() == 1 ? "" : "es")
                    + "; details: mealiq recipes show \"NAME\"");
            printTimeNote(shown);
        }
        if (!response.conflicts().isEmpty()) {
            out.println("Left out because of your allergies or diet:");
            response.conflicts().forEach(c -> out.println("  " + c));
        }
        return MealPlanningCLI.OK;
    }

    @Command(name = "find", description = {
        "Ask the AI agent for recipes matching your words; it searches, ranks and explains.",
        "Example: mealiq recipes find \"quick high-protein dinner\" --max-minutes 20",
        "Filter options are hard limits the agent cannot widen."})
    int find(@Parameters(paramLabel = "REQUEST", description = "What you want, in your own words") String request,
             @Option(names = "--meal", paramLabel = "TYPE") MealType meal,
             @Option(names = "--max-minutes", paramLabel = "N") Integer maxMinutes,
             @Option(names = "--include", paramLabel = "INGREDIENTS", split = ",") List<String> include,
             @Option(names = "--exclude", paramLabel = "INGREDIENTS", split = ",") List<String> exclude,
             @Option(names = "--rank", paramLabel = "BY", defaultValue = "nutrition",
                     description = "Ranking the agent chooses from: nutrition, time or budget "
                             + "(default: ${DEFAULT-VALUE})")
             Rank rank,
             @Option(names = "--show-steps", description = "Also print the agent's tool calls") boolean showSteps) {
        RecipeQuery filters = new RecipeQuery(meal, maxMinutes, include, exclude, null, false);
        AgentResponse response = cli.facade().recommendRecipe(cli.profileId(), request, filters, rank.mode);
        PrintWriter out = cli.out();
        if (response.status() == ResponseStatus.SUCCESS) {
            printTable(response.recipes(), "Recommended");
            printTimeNote(response.recipes());
            out.println("Why: " + response.rationale());
        } else {
            out.println(response.rationale());
        }
        response.conflicts().forEach(c -> out.println("Note: " + c));
        if (showSteps) {
            out.println("Agent steps:");
            if (response.agentSteps().isEmpty()) {
                out.println("  (none)");
            }
            for (int i = 0; i < response.agentSteps().size(); i++) {
                out.println("  " + (i + 1) + ". " + response.agentSteps().get(i).replace("\n", "\n     "));
            }
        }
        if (response.status() == ResponseStatus.SUCCESS) {
            out.println("Values come from USDA FoodData Central. A planning aid, not dietary advice.");
            return MealPlanningCLI.OK;
        }
        return MealPlanningCLI.FAILED;
    }

    private void printRecipe(Recipe recipe, int servings) {
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
    }

    private void printTable(List<Recipe> recipes, String heading) {
        OptionalInt timeLimit = cli.facade().cookingLimitToday(cli.profileId());
        cli.out().printf("  %-40s %6s %7s %9s %8s%n", heading, "min", "kcal", "protein", "cost");
        for (Recipe recipe : recipes) {
            NutritionSummary serving = cli.facade().recipeNutrition(recipe, 1);
            boolean over = timeLimit.isPresent() && recipe.totalMinutes() > timeLimit.getAsInt();
            cli.out().printf("  %-40s %6s %7s %9s %8s%n", recipe.name(), recipe.totalMinutes() + (over ? "*" : ""),
                    serving.complete() ? String.valueOf(Math.round(serving.calories())) : "?",
                    serving.complete() ? Math.round(serving.proteinG()) + " g" : "?",
                    cli.facade().recipeCost(cli.profileId(), recipe).toString());
        }
    }

    private void printTimeNote(List<Recipe> recipes) {
        OptionalInt timeLimit = cli.facade().cookingLimitToday(cli.profileId());
        if (timeLimit.isPresent() && recipes.stream().anyMatch(r -> r.totalMinutes() > timeLimit.getAsInt())) {
            cli.out().println("* Over today's cooking-time limit of " + timeLimit.getAsInt() + " minutes.");
        }
    }

    @Command(name = "generate", description = {
        "Ask the AI to write a new recipe from catalogue ingredients you can eat; you see it before saving.",
        "Example: mealiq recipes generate \"warm vegan breakfast\" --meal breakfast --max-minutes 15 --use oats"})
    int generate(@Parameters(paramLabel = "REQUEST", arity = "0..1", description = "What you want, in your own words")
                 String request,
                 @Option(names = "--meal", paramLabel = "TYPE") MealType meal,
                 @Option(names = "--max-minutes", paramLabel = "N") Integer maxMinutes,
                 @Option(names = "--use", paramLabel = "INGREDIENTS", split = ",",
                         description = "Ingredients the recipe must use") List<String> use,
                 @Option(names = "--exclude", paramLabel = "INGREDIENTS", split = ",") List<String> exclude,
                 @Option(names = {"-y", "--yes"}, description = "Save without asking") boolean yes) {
        RecipeQuery filters = new RecipeQuery(meal, maxMinutes, use, exclude, null, true);
        AgentResponse response = cli.facade().generateRecipe(cli.profileId(), request, filters);
        PrintWriter out = cli.out();
        if (response.status() != ResponseStatus.NEEDS_CONFIRMATION) {
            out.println(response.rationale());
            response.conflicts().forEach(c -> out.println("  " + c));
            return MealPlanningCLI.FAILED;
        }
        Recipe recipe = response.recipes().getFirst();
        printRecipe(recipe, 1);
        out.println("Cost per serving: " + cli.facade().recipeCost(cli.profileId(), recipe) + ".");
        out.println(response.rationale());
        response.conflicts().forEach(c -> out.println("Note: " + c));
        if (!yes && !cli.confirm("Save this recipe?")) {
            out.println("Not saved.");
            return MealPlanningCLI.OK;
        }
        out.println(cli.facade().saveGeneratedRecipe(recipe.id()).rationale()
                + " See it any time with: mealiq recipes show \"" + recipe.name() + "\"");
        return MealPlanningCLI.OK;
    }

    @Command(name = "show", description = "Ingredients, steps and nutrition of one recipe.")
    int show(@Parameters(paramLabel = "NAME") String name,
             @Option(names = "--servings", paramLabel = "N", defaultValue = "1",
                     description = "Servings for the nutrition total (default: ${DEFAULT-VALUE})") int servings) {
        Recipe recipe = cli.facade().findRecipe(name);
        PrintWriter out = cli.out();
        printRecipe(recipe, servings);
        ValidationResult check = cli.facade().checkRecipe(cli.profileId(), recipe);
        check.violations().forEach(v -> out.println("Not suitable: " + v));
        check.warnings().forEach(w -> out.println("Note: " + w));
        out.println("Values come from USDA FoodData Central. A planning aid, not dietary advice.");
        return MealPlanningCLI.OK;
    }
}
