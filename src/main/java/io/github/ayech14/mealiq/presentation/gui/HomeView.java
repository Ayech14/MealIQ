package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.contracts.Checkup;
import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The home screen: today at a glance and the agent's check-up. The agent looks at the plan,
 * pantry, budget and recent changes, and proposes the next step; accepting sends that request
 * to the agent, which still previews any change before applying it (D03).
 */
final class HomeView {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH);

    private final GuiContext context;
    private final Label greeting = Ui.styled(new Label(), "page-title");
    private final Label date = Ui.styled(new Label(), "page-subtitle");
    private final HBox stats = new HBox(14);
    private final VBox today = Ui.card("Today's meals");
    private final VBox checkup = Ui.card("Agent check-up");
    private final VBox root;

    HomeView(GuiContext context) {
        this.context = context;
        TextField ask = new TextField();
        ask.setPromptText("Tell the agent what you want, e.g. \"plan 3 cheap vegetarian days\"");
        HBox.setHgrow(ask, Priority.ALWAYS);
        Button send = Ui.primary(new Button("Ask"));
        send.disableProperty().bind(context.busy());
        Runnable askNow = () -> {
            if (!ask.getText().isBlank()) {
                context.askAgent(ask.getText().strip());
                ask.clear();
            }
        };
        send.setOnAction(e -> askNow.run());
        ask.setOnAction(e -> askNow.run());
        FlowPane ideas = new FlowPane(8, 8);
        for (String idea : AssistantView.SUGGESTIONS) {
            Hyperlink chip = Ui.styled(new Hyperlink(idea), "chip");
            chip.setOnAction(e -> context.askAgent(idea));
            chip.disableProperty().bind(context.busy());
            ideas.getChildren().add(chip);
        }
        VBox askCard = Ui.card("Ask the agent", Ui.row(8, ask, send), ideas);

        HBox columns = new HBox(14, today, checkup);
        columns.setFillHeight(false);
        HBox.setHgrow(today, Priority.ALWAYS);
        HBox.setHgrow(checkup, Priority.ALWAYS);
        today.setPrefWidth(420);
        checkup.setPrefWidth(460);
        VBox header = new VBox(2, greeting, date);
        root = new VBox(header, stats, columns, askCard);
        root.getStyleClass().add("page");
        context.onDataChanged(this::load);
    }

    Node view() {
        return Ui.scrolling(root);
    }

    void load() {
        context.run("Checking your plan, pantry and budget...",
                () -> context.facade().checkup(context.profileId()), this::show);
    }

    private void show(Checkup result) {
        int hour = LocalTime.now().getHour();
        String part = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
        greeting.setText(part + (context.profileName().equals("default") ? "" : ", " + context.profileName()));
        LocalDate day = context.facade().today();
        date.setText(day.format(DATE) + ". Here is what your agent sees today.");

        stats.getChildren().setAll(todayStat(result), pantryStat(result), groceryStat(result));
        stats.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        today.getChildren().setAll(Ui.styled(new Label("Today's meals"), "card-title"));
        if (result.todaysMeals().isEmpty()) {
            today.getChildren().add(Ui.wrapped(result.plan().isPresent() ? "Nothing planned for today."
                    : "No current meal plan.", "muted"));
            Button plan = Ui.primary(new Button("Plan my week"));
            plan.disableProperty().bind(context.busy());
            plan.setOnAction(e -> context.askAgent("Plan 7 days of meals starting today"));
            today.getChildren().add(plan);
        } else {
            for (PlannedMeal meal : result.todaysMeals()) {
                NutritionSummary n = context.facade().recipeNutrition(meal.recipe(), meal.servings());
                Hyperlink name = Ui.styled(new Hyperlink(meal.recipe().name()
                        + (meal.servings() > 1 ? "  x" + meal.servings() : "")), "recipe-link");
                name.setOnAction(e -> context.showInfo(meal.recipe().name(),
                        Ui.describe(context.facade(), context.profileId(), meal.recipe())));
                today.getChildren().add(Ui.row(10, Ui.mealTag(meal.mealType()), name, Ui.spacer(),
                        Ui.styled(new Label(meal.recipe().totalMinutes() + " min  ·  " + Ui.kcal(n) + " kcal"),
                                "muted")));
            }
            Button open = Ui.ghost(new Button("Open the plan"));
            open.setOnAction(e -> context.navigate("plan"));
            today.getChildren().add(open);
        }

        checkup.getChildren().setAll(Ui.styled(new Label("Agent check-up"), "card-title"),
                Ui.wrapped("The agent checks your plan, pantry and budget and proposes the next step. "
                        + "Nothing changes until you approve it.", "muted"));
        if (result.suggestions().isEmpty()) {
            checkup.getChildren().add(Ui.banner("success", "All good",
                    List.of("Your plan fits your profile, pantry and budget.")));
        }
        for (Checkup.Suggestion s : result.suggestions()) {
            VBox banner = Ui.banner(s.level() == Checkup.Level.WARNING ? "warning" : "success", s.title(),
                    List.of(s.detail()));
            Button act = s.level() == Checkup.Level.WARNING ? Ui.primary(new Button(s.actionLabel()))
                    : Ui.ghost(new Button(s.actionLabel()));
            act.disableProperty().bind(context.busy());
            act.setOnAction(e -> {
                if (s.agentRequest() != null) {
                    context.askAgent(s.agentRequest());
                } else {
                    context.navigate(s.page());
                }
            });
            banner.getChildren().add(act);
            checkup.getChildren().add(banner);
        }
    }

    private VBox todayStat(Checkup result) {
        String value = result.todaysNutrition().map(n -> Ui.kcal(n) + " kcal").orElse("-");
        String target = result.profile().map(UserProfile::dailyCalorieTarget)
                .map(t -> "Planned today, target " + t + " kcal").orElse("Planned today");
        String protein = result.todaysNutrition().map(n -> Ui.protein(n) + " protein").orElse("No meals today");
        return stat(value, target, protein);
    }

    private VBox pantryStat(Checkup result) {
        return stat(String.valueOf(result.pantryItems()), "Items in your pantry",
                result.expiringSoon() == 0 ? "Nothing expiring soon" : result.expiringSoon() + " expiring soon");
    }

    private VBox groceryStat(Checkup result) {
        String value = result.itemsToBuy().map(String::valueOf).orElse("-");
        String line = result.budget().map(HomeView::budgetLine)
                .orElse(result.itemsToBuy().isPresent() ? "No budget set" : "No shopping list yet");
        return stat(value, "Items to buy", line);
    }

    private static String budgetLine(BudgetStatus b) {
        return "$" + b.estimate() + (b.partial() ? "+" : "") + " of $" + b.budget()
                + (b.withinBudget() ? ", within budget" : ", over budget");
    }

    private static VBox stat(String value, String label, String note) {
        VBox card = Ui.card(null, Ui.styled(new Label(value), "stat-value"), Ui.styled(new Label(label), "stat-label"),
                Ui.wrapped(note, "muted"));
        card.setSpacing(2);
        card.setPrefWidth(260);
        return card;
    }
}
