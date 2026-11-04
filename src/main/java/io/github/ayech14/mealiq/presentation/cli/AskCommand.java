package io.github.ayech14.mealiq.presentation.cli;

import io.github.ayech14.mealiq.application.AssistantReply;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.AssistantRequest;
import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.Locale;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code mealiq ask "..."}: tell the agent what you want in your own words; it picks the action. */
@Command(name = "ask", mixinStandardHelpOptions = true, description = {
    "Tell the AI agent what you want; it works out the action and asks before changing anything.",
    "Examples: mealiq ask \"I ran out of eggs\"   mealiq ask \"plan 3 cheap vegetarian days\""})
final class AskCommand implements Runnable {

    @ParentCommand
    MealPlanningCLI cli;

    @Parameters(paramLabel = "MESSAGE", arity = "1..*", description = "What you want, in your own words")
    String[] words;

    @Option(names = {"-y", "--yes"}, description = "Apply proposed changes without asking")
    boolean yes;

    @Option(names = "--show-steps", description = "Also print the agent's tool calls")
    boolean showSteps;

    @Override
    public void run() {
        PrintWriter out = cli.out();
        AssistantReply reply = cli.facade().ask(cli.profileId(), String.join(" ", words));
        if (!reply.text().isBlank()) {
            out.println(reply.text());
        }
        if (reply.pending() != null) {
            out.println(reply.pending().title());
            reply.pending().lines().forEach(line -> out.println("  " + line));
            if (yes || cli.confirm("Apply?")) {
                AgentResponse done = cli.facade().confirmUpdate(cli.profileId(), reply.pending().id());
                out.println(done.rationale());
                done.conflicts().forEach(c -> out.println("Note: " + c));
            } else {
                out.println("Cancelled. Nothing was changed.");
            }
        }
        if (reply.result() != null) {
            show(reply.result(), out);
            if (reply.action() == AssistantRequest.Action.RECIPES && reply.result().recipes().isEmpty()
                    && reply.result().status() == ResponseStatus.CONFLICT) {
                out.println("To have one written: mealiq ask \"write a new recipe for " + String.join(" ", words)
                        + "\"");
            }
        }
    }

    private void show(AgentResponse result, PrintWriter out) {
        if (result.change() != null && result.status() == ResponseStatus.NEEDS_CONFIRMATION) {
            out.println("Proposed change:");
            for (PlannedMeal proposed : result.change().proposal().meals()) {
                PlannedMeal current = result.plan().findMeal(proposed.id());
                out.println("  " + current.date() + " " + current.mealType().name().toLowerCase(Locale.ROOT) + ": "
                        + current.recipe().name() + " -> " + proposed.recipe().name());
            }
            if (!result.rationale().isEmpty()) {
                out.println("Why: " + result.rationale());
            }
            result.conflicts().forEach(c -> out.println("Note: " + c));
            if (yes || cli.promptConfirmation(result.change())) {
                out.println(cli.facade().executeChange(result.plan().id(), result.change()).rationale());
            } else {
                out.println("Cancelled. The plan is unchanged.");
            }
        } else if (result.status() == ResponseStatus.NEEDS_CONFIRMATION && !result.recipes().isEmpty()) {
            Recipe recipe = result.recipes().getFirst();
            out.println(recipe.name() + " (" + recipe.totalMinutes() + " min). Details: mealiq recipes show \""
                    + recipe.name() + "\" after saving.");
            if (yes || cli.confirm("Save this recipe?")) {
                out.println(cli.facade().saveGeneratedRecipe(recipe.id()).rationale());
            }
        } else {
            if (!result.rationale().isEmpty()) {
                out.println(result.rationale());
            }
            result.recipes().forEach(r -> out.println("  " + r.name() + " (" + r.totalMinutes() + " min)"));
            if (result.plan() != null) {
                result.plan().meals().stream().sorted(Comparator.comparing(PlannedMeal::date)
                        .thenComparing(PlannedMeal::mealType)).forEach(m -> out.println("  " + m.date() + " "
                        + m.mealType().name().toLowerCase(Locale.ROOT) + ": " + m.recipe().name()));
            }
            if (result.groceryList() != null) {
                long toBuy = result.groceryList().items().stream().filter((GroceryItem i) -> !i.toBuy().isZero())
                        .count();
                out.println("  " + toBuy + " item(s) to buy, at least $" + result.groceryList().total()
                        + ". Full list: mealiq grocery show");
            }
            result.conflicts().forEach(c -> out.println("Note: " + c));
        }
        if (showSteps) {
            for (int i = 0; i < result.agentSteps().size(); i++) {
                out.println("  step " + (i + 1) + ". " + result.agentSteps().get(i));
            }
        }
    }
}
