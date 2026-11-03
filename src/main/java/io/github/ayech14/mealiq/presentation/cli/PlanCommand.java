package io.github.ayech14.mealiq.presentation.cli;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanChange;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.domain.AppliedChange;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.services.NotFoundException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * {@code mealiq plan generate|show|modify|substitute|cheaper|undo|history}: weekly plans (F05,
 * F06) and confirmed changes with undo (F08, F09, F10).
 */
@Command(name = "plan", description = "Generate or show your meal plan.", mixinStandardHelpOptions = true)
final class PlanCommand implements Runnable {

    /** CLI names for the planning modes. */
    enum Mode {
        NUTRITION(PlanningMode.NUTRITION_GOAL), TIME(PlanningMode.TIME_AWARE), BUDGET(PlanningMode.BUDGET);

        final PlanningMode mode;

        Mode(PlanningMode mode) {
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

    @Command(name = "generate", description = {
        "Ask the AI agent for a plan; it is saved only if it passes every check.",
        "Example: mealiq plan generate --days 3 --mode time"})
    int generate(@Option(names = "--start", paramLabel = "YYYY-MM-DD", description = "First day (default: today)")
                 LocalDate start,
                 @Option(names = "--days", paramLabel = "N", defaultValue = "7",
                         description = "1 to " + PlanRequest.MAX_DAYS + " (default: ${DEFAULT-VALUE})") int days,
                 @Option(names = "--mode", paramLabel = "MODE", defaultValue = "nutrition",
                         description = "nutrition, time or budget (default: ${DEFAULT-VALUE})") Mode mode,
                 @Option(names = "--note", paramLabel = "TEXT", description = "Anything else, e.g. \"more fish\"")
                 String note,
                 @Option(names = "--show-steps", description = "Also print the agent's tool calls") boolean showSteps) {
        if (days < 1 || days > PlanRequest.MAX_DAYS) {
            cli.out().println("--days must be between 1 and " + PlanRequest.MAX_DAYS + ".");
            return MealPlanningCLI.FAILED;
        }
        PlanRequest request = new PlanRequest(cli.profileId(), start == null ? cli.facade().today() : start, days,
                mode.mode, note);
        AgentResponse response = cli.facade().generateWeeklyPlan(request);
        PrintWriter out = cli.out();
        if (response.status() == ResponseStatus.SUCCESS) {
            out.println("Plan saved.");
            print(response.plan());
            out.println("Why: " + response.rationale());
        } else {
            out.println(response.rationale());
        }
        response.conflicts().forEach(c -> out.println((response.status() == ResponseStatus.SUCCESS ? "Note: " : "  ")
                + c));
        if (showSteps) {
            printSteps(response);
        }
        if (response.status() == ResponseStatus.SUCCESS) {
            printDisclaimer();
            return MealPlanningCLI.OK;
        }
        return MealPlanningCLI.FAILED;
    }

    @Command(name = "modify", description = {
        "Change the plan in your own words; you see the change before it is applied.",
        "Example: mealiq plan modify \"make Wednesday's dinner vegetarian\""})
    int modify(@Parameters(paramLabel = "REQUEST") String request,
               @Option(names = "--mode", paramLabel = "MODE", defaultValue = "nutrition",
                       description = "Ranking for candidates: nutrition, time or budget (default: ${DEFAULT-VALUE})")
               Mode mode,
               @Option(names = {"-y", "--yes"}, description = "Apply without asking") boolean yes,
               @Option(names = "--show-steps", description = "Also print the agent's tool calls") boolean showSteps) {
        return confirmFlow(cli.facade().modifyPlan(cli.profileId(), request, mode.mode), yes, showSteps);
    }

    @Command(name = "substitute", description = {
        "Replace an ingredient in every planned meal that uses it.",
        "Example: mealiq plan substitute milk"})
    int substitute(@Parameters(paramLabel = "INGREDIENT") String ingredient,
                   @Option(names = {"-y", "--yes"}, description = "Apply without asking") boolean yes,
                   @Option(names = "--show-steps", description = "Also print the agent's tool calls")
                   boolean showSteps) {
        return confirmFlow(cli.facade().requestSubstitution(cli.profileId(), ingredient), yes, showSteps);
    }

    @Command(name = "cheaper", description = "When the groceries are over budget, swap meals for cheaper ones.")
    int cheaper(@Option(names = {"-y", "--yes"}, description = "Apply without asking") boolean yes,
                @Option(names = "--show-steps", description = "Also print the agent's tool calls") boolean showSteps) {
        return confirmFlow(cli.facade().suggestCheaperPlan(cli.profileId()), yes, showSteps);
    }

    @Command(name = "undo", description = "Undo the last applied change.")
    int undo() {
        AgentResponse response = cli.facade().undoLastChange(cli.profileId());
        cli.out().println(response.rationale());
        if (response.status() != ResponseStatus.SUCCESS) {
            return MealPlanningCLI.FAILED;
        }
        printGroceryLine(response);
        return MealPlanningCLI.OK;
    }

    @Command(name = "history", description = "List the applied changes that can be undone, oldest first.")
    int history() {
        List<AppliedChange> changes = cli.facade().changeHistory(cli.profileId());
        if (changes.isEmpty()) {
            cli.out().println("No changes yet.");
        }
        for (int i = 0; i < changes.size(); i++) {
            cli.out().println("  " + (i + 1) + ". " + changes.get(i).description());
        }
        return MealPlanningCLI.OK;
    }

    /** Shows the agent's answer; a proposed change is previewed and applied only if confirmed. */
    private int confirmFlow(AgentResponse response, boolean yes, boolean showSteps) {
        PrintWriter out = cli.out();
        if (showSteps) {
            printSteps(response);
        }
        switch (response.status()) {
            case NEEDS_CONFIRMATION -> {
                return previewAndConfirm(response, yes);
            }
            case NEEDS_CLARIFICATION -> out.println("Question: " + response.rationale()
                    + " Ask again with more detail.");
            case SUCCESS -> out.println(response.rationale());
            default -> {
                out.println(response.rationale());
                response.conflicts().forEach(c -> out.println("  " + c));
            }
        }
        return response.status() == ResponseStatus.SUCCESS ? MealPlanningCLI.OK : MealPlanningCLI.FAILED;
    }

    private int previewAndConfirm(AgentResponse response, boolean yes) {
        PrintWriter out = cli.out();
        PlanChange change = response.change();
        out.println("Proposed change:");
        for (PlannedMeal proposed : change.proposal().meals()) {
            PlannedMeal current = response.plan().findMeal(proposed.id());
            NutritionSummary n = cli.facade().recipeNutrition(proposed.recipe(), proposed.servings());
            out.println("  " + current.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " "
                    + current.date() + " " + current.mealType().name().toLowerCase(Locale.ROOT) + ": "
                    + current.recipe().name() + " -> " + proposed.recipe().name() + " (" + proposed.recipe()
                    .totalMinutes() + " min, " + kcal(n) + " kcal, " + protein(n) + " protein)");
        }
        if (!response.rationale().isEmpty()) {
            out.println("Why: " + response.rationale());
        }
        response.conflicts().forEach(c -> out.println("Note: " + c));
        if (!yes && !cli.promptConfirmation(change)) {
            out.println("Cancelled. The plan is unchanged.");
            return MealPlanningCLI.OK;
        }
        AgentResponse applied = cli.facade().executeChange(response.plan().id(), change);
        if (applied.status() != ResponseStatus.SUCCESS) {
            out.println(applied.rationale());
            return MealPlanningCLI.FAILED;
        }
        out.println("Applied: " + applied.rationale() + ". Undo with: mealiq plan undo");
        printGroceryLine(applied);
        return MealPlanningCLI.OK;
    }

    private void printGroceryLine(AgentResponse response) {
        GroceryList list = response.groceryList();
        if (list != null) {
            long toBuy = list.items().stream().filter(i -> !i.toBuy().isZero()).count();
            cli.out().println("Grocery list refreshed: " + toBuy + " item(s) to buy, at least $" + list.total() + ".");
        }
    }

    private void printSteps(AgentResponse response) {
        cli.out().println("Agent steps:");
        if (response.agentSteps().isEmpty()) {
            cli.out().println("  (none)");
        }
        for (int i = 0; i < response.agentSteps().size(); i++) {
            cli.out().println("  " + (i + 1) + ". " + response.agentSteps().get(i).replace("\n", "\n     "));
        }
    }

    @Command(name = "show", description = "Show the active plan with nutrition per day.")
    int show() {
        Optional<MealPlan> plan = cli.facade().getActivePlan(cli.profileId());
        if (plan.isEmpty()) {
            cli.out().println("No plan yet. Create one with: mealiq plan generate");
            return MealPlanningCLI.FAILED;
        }
        print(plan.get());
        printDisclaimer();
        return MealPlanningCLI.OK;
    }

    private void print(MealPlan plan) {
        PrintWriter out = cli.out();
        UserProfile profile = profile();
        Map<LocalDate, NutritionSummary> daily = cli.facade().dailyNutrition(plan);
        out.println("Plan " + plan.startDate() + " to " + plan.endDate() + " (" + plan.days() + " day"
                + (plan.days() == 1 ? "" : "s") + ")");
        for (Map.Entry<LocalDate, NutritionSummary> day : daily.entrySet()) {
            LocalDate date = day.getKey();
            out.printf("%-48s %5s %6s %8s%n", date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    + " " + date, "min", "kcal", "protein");
            List<PlannedMeal> meals = plan.mealsOn(date).stream()
                    .sorted(Comparator.comparing(PlannedMeal::mealType)).toList();
            for (PlannedMeal meal : meals) {
                NutritionSummary n = cli.facade().recipeNutrition(meal.recipe(), meal.servings());
                out.printf("  %-10s %-35s %5d %6s %8s%n", meal.mealType().name().toLowerCase(Locale.ROOT),
                        meal.recipe().name(), meal.recipe().totalMinutes(), kcal(n), protein(n));
            }
            NutritionSummary total = day.getValue();
            String target = profile == null ? "" : targets(profile);
            out.printf("  %-46s %6s %8s%s%n", "Day total", kcal(total), protein(total), target);
        }
    }

    private void printDisclaimer() {
        cli.out().println("Values come from USDA FoodData Central. A planning aid, not dietary advice.");
    }

    private UserProfile profile() {
        try {
            return cli.facade().getProfile(cli.profileId());
        } catch (NotFoundException e) {
            return null;
        }
    }

    private static String targets(UserProfile profile) {
        if (profile.dailyCalorieTarget() == null && profile.dailyProteinTargetG() == null) {
            return "";
        }
        return "   (target " + (profile.dailyCalorieTarget() == null ? "-" : profile.dailyCalorieTarget() + " kcal")
                + ", " + (profile.dailyProteinTargetG() == null ? "-" : profile.dailyProteinTargetG() + " g") + ")";
    }

    private static String kcal(NutritionSummary n) {
        return n.complete() ? String.valueOf(Math.round(n.calories())) : "?";
    }

    private static String protein(NutritionSummary n) {
        return n.complete() ? Math.round(n.proteinG()) + " g" : "?";
    }
}
