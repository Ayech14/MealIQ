package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.application.AssistantReply;
import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.AssistantRequest;
import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The main screen: a conversation with the agent. The user types what they want; the agent
 * works out the action, runs it through the facade, and shows the result with the steps it
 * took. Anything that changes data waits for Apply (D03).
 */
final class AssistantView {

    static final List<String> SUGGESTIONS = List.of(
            "Plan 3 days of quick meals", "Suggest a high-protein dinner under 30 minutes", "I ran out of eggs",
            "I'm vegetarian and allergic to peanuts", "Make my plan cheaper", "What do I need to buy?");

    private final GuiContext context;
    private final VBox conversation = new VBox(12);
    private final ScrollPane scroll = new ScrollPane(conversation);
    private final TextField input = new TextField();
    private final VBox root = new VBox(10);

    AssistantView(GuiContext context) {
        this.context = context;
        conversation.setPadding(new Insets(12));
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        conversation.heightProperty().addListener((obs, old, height) -> scroll.setVvalue(1.0));

        input.setPromptText("Tell MealIQ what you want, e.g. \"plan 3 cheap vegetarian days\"");
        input.setOnAction(e -> send(input.getText()));
        HBox.setHgrow(input, Priority.ALWAYS);
        Button send = Ui.primary(new Button("Send"));
        send.setDefaultButton(true);
        send.setOnAction(e -> send(input.getText()));
        send.disableProperty().bind(context.busy());
        input.disableProperty().bind(context.busy());

        FlowPane chips = new FlowPane(8, 8);
        for (String suggestion : SUGGESTIONS) {
            Hyperlink chip = Ui.styled(new Hyperlink(suggestion), "chip");
            chip.setOnAction(e -> send(suggestion));
            chip.disableProperty().bind(context.busy());
            chips.getChildren().add(chip);
        }
        scroll.getStyleClass().add("page-scroll");
        root.getStyleClass().add("page");
        root.getChildren().addAll(Ui.styled(new Label("Ask the agent"), "page-title"),
                Ui.wrapped("Say what you want. The agent picks the action, uses its tools (recipe search, nutrition, "
                        + "groceries, substitutions) and shows you any change before making it.", "page-subtitle"),
                scroll, chips, Ui.row(8, input, send));
        agentSays(new VBox(6, wrapped("Hi! I'm the MealIQ agent. Tell me about your diet and goals, what's in your "
                + "pantry, or ask for recipes, a meal plan or a shopping list. I use tools to search recipes and "
                + "check nutrition and prices, and I always show you a change before I make it."),
                wrapped("A planning aid, not dietary advice.")));
    }

    Node view() {
        return root;
    }

    void send(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        input.clear();
        userSays(message);
        UUID profileId = context.profileId();
        context.run("The agent is working on it...", () -> context.facade().ask(profileId, message),
                reply -> show(message, reply));
    }

    private void show(String message, AssistantReply reply) {
        VBox card = new VBox(8);
        if (!reply.text().isBlank()) {
            card.getChildren().add(wrapped(reply.text()));
        }
        if (reply.pending() != null) {
            card.getChildren().add(bold(reply.pending().title()));
            reply.pending().lines().forEach(line -> card.getChildren().add(wrapped("  " + line)));
            UUID updateId = reply.pending().id();
            card.getChildren().add(choice("Apply", "Cancel", () -> context.facade().confirmUpdate(context.profileId(),
                    updateId), card));
        }
        AgentResponse result = reply.result();
        if (result != null) {
            showResult(result, card);
            if (reply.action() == AssistantRequest.Action.RECIPES && result.recipes().isEmpty()
                    && result.status() == ResponseStatus.CONFLICT) {
                Button write = new Button("Write one for me");
                write.disableProperty().bind(context.busy());
                write.setOnAction(e -> send("Write a new recipe for: " + message));
                card.getChildren().add(write);
            }
        }
        agentSays(card);
    }

    private void showResult(AgentResponse result, VBox card) {
        if (result.status() == ResponseStatus.FAILED || result.status() == ResponseStatus.NEEDS_CLARIFICATION
                || (result.status() == ResponseStatus.CONFLICT && result.recipes().isEmpty()
                        && result.change() == null && result.groceryList() == null)) {
            card.getChildren().add(wrapped(result.rationale()));
        }
        if (result.change() != null && result.status() == ResponseStatus.NEEDS_CONFIRMATION) {
            card.getChildren().add(bold("Proposed change:"));
            for (PlannedMeal proposed : result.change().proposal().meals()) {
                PlannedMeal current = result.plan().findMeal(proposed.id());
                card.getChildren().add(wrapped("  " + day(current.date()) + " " + type(current) + ": "
                        + current.recipe().name() + "  ->  " + proposed.recipe().name() + " ("
                        + proposed.recipe().totalMinutes() + " min)"));
            }
            if (!result.rationale().isEmpty()) {
                card.getChildren().add(wrapped("Why: " + result.rationale()));
            }
            UUID planId = result.plan().id();
            card.getChildren().add(choice("Apply", "Cancel",
                    () -> context.facade().executeChange(planId, result.change()), card));
        } else if (result.status() == ResponseStatus.NEEDS_CONFIRMATION && !result.recipes().isEmpty()) {
            Recipe recipe = result.recipes().getFirst();
            card.getChildren().add(wrapped(Ui.describe(context.facade(), context.profileId(), recipe)));
            card.getChildren().add(choice("Save recipe", "Discard",
                    () -> context.facade().saveGeneratedRecipe(recipe.id()), card));
        } else if (!result.recipes().isEmpty()) {
            if (!result.rationale().isEmpty()) {
                card.getChildren().add(wrapped(result.rationale()));
            }
            for (Recipe recipe : result.recipes()) {
                NutritionSummary n = context.facade().recipeNutrition(recipe, 1);
                Hyperlink link = new Hyperlink(recipe.name() + "  -  " + recipe.totalMinutes() + " min, "
                        + Ui.kcal(n) + " kcal, " + Ui.protein(n) + " protein");
                link.setOnAction(e -> context.showInfo(recipe.name(),
                        Ui.describe(context.facade(), context.profileId(), recipe)));
                card.getChildren().add(link);
            }
        } else if (result.plan() != null) {
            showPlan(result.plan(), result.status() == ResponseStatus.SUCCESS && result.change() == null
                    && !result.agentSteps().isEmpty() ? "Plan saved:" : "Your plan:", card);
            if (!result.rationale().isEmpty() && !result.agentSteps().isEmpty()) {
                card.getChildren().add(wrapped("Why: " + result.rationale()));
            }
            context.dataChanged();
        }
        if (result.groceryList() != null && result.change() == null) {
            List<GroceryItem> toBuy = result.groceryList().items().stream().filter(i -> !i.toBuy().isZero())
                    .toList();
            card.getChildren().add(bold(toBuy.size() + " item(s) to buy, at least $" + result.groceryList().total()));
            toBuy.stream().limit(12).forEach(i -> card.getChildren().add(wrapped("  " + i.ingredient().name() + " "
                    + i.toBuy() + (i.estimatedPrice() == null ? "" : "  $" + i.estimatedPrice()))));
            if (toBuy.size() > 12) {
                card.getChildren().add(wrapped("  ... the full list is on the Groceries tab."));
            }
            if (result.budgetStatus() != null) {
                card.getChildren().add(wrapped(result.budgetStatus().withinBudget()
                        ? "Within the budget of $" + result.budgetStatus().budget() + " for this plan."
                        : "Over the budget of $" + result.budgetStatus().budget() + " by $"
                                + result.budgetStatus().shortfall() + ". Ask me to make the plan cheaper."));
            }
            context.dataChanged();
        }
        result.conflicts().forEach(c -> card.getChildren().add(wrapped("Note: " + c)));
        if (!result.agentSteps().isEmpty()) {
            VBox steps = new VBox(4);
            for (int i = 0; i < result.agentSteps().size(); i++) {
                steps.getChildren().add(wrapped((i + 1) + ". " + result.agentSteps().get(i)));
            }
            int count = result.agentSteps().size();
            TitledPane how = new TitledPane("How I did it (" + count + " tool call" + (count == 1 ? "" : "s") + ")",
                    steps);
            how.setExpanded(false);
            card.getChildren().add(how);
        }
    }

    private void showPlan(MealPlan plan, String heading, VBox card) {
        card.getChildren().add(bold(heading + " " + plan.startDate() + " to " + plan.endDate()));
        for (int day = 0; day < plan.days(); day++) {
            LocalDate date = plan.startDate().plusDays(day);
            String meals = String.join(", ", plan.mealsOn(date).stream()
                    .sorted(Comparator.comparing(PlannedMeal::mealType)).map(m -> type(m) + ": " + m.recipe().name())
                    .toList());
            card.getChildren().add(wrapped("  " + day(date) + "  " + meals));
        }
    }

    /** Apply and Cancel buttons; after either, they are replaced by what happened. */
    private Node choice(String yes, String no, Supplier<AgentResponse> apply, VBox card) {
        Button accept = Ui.primary(new Button(yes));
        Button decline = new Button(no);
        HBox buttons = Ui.row(8, accept, decline);
        accept.disableProperty().bind(context.busy());
        decline.setOnAction(e -> card.getChildren().set(card.getChildren().indexOf(buttons),
                wrapped("Cancelled. Nothing was changed.")));
        accept.setOnAction(e -> context.run("Applying...", apply, response -> {
            String text = response.status() == ResponseStatus.SUCCESS
                    ? "Applied. " + response.rationale() : response.rationale();
            VBox done = new VBox(4, wrapped(text));
            response.conflicts().forEach(c -> done.getChildren().add(wrapped("Note: " + c)));
            card.getChildren().set(card.getChildren().indexOf(buttons), done);
            context.dataChanged();
        }));
        return buttons;
    }

    private void userSays(String text) {
        Label bubble = wrapped(text);
        bubble.getStyleClass().add("bubble-user");
        bubble.setMaxWidth(560);
        HBox line = new HBox(bubble);
        line.setAlignment(Pos.CENTER_RIGHT);
        conversation.getChildren().add(line);
    }

    private void agentSays(VBox content) {
        content.getStyleClass().add("bubble-agent");
        content.getChildren().addFirst(Ui.styled(new Label("MealIQ agent"), "agent-name"));
        content.setMaxWidth(820);
        HBox line = new HBox(content);
        line.setAlignment(Pos.CENTER_LEFT);
        conversation.getChildren().add(line);
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(800);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label bold(String text) {
        Label label = wrapped(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static String day(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + date;
    }

    private static String type(PlannedMeal meal) {
        return meal.mealType().name().toLowerCase(Locale.ROOT);
    }
}
