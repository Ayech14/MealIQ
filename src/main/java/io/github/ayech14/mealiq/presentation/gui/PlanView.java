package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.PlanChange;
import io.github.ayech14.mealiq.contracts.PlanImpact;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.services.NotFoundException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The meal plan (F05, F06) and every way to change it: typed changes, substitutions,
 * cheaper meals, adaptation and undo (F08 to F11, F13). A new plan is saved only when it
 * passes every check; each change is previewed and applied only after the user confirms (D03).
 */
final class PlanView {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);

    /** One day of the plan with its totals, built off the JavaFX thread. */
    private record Day(LocalDate date, List<PlannedMeal> meals, List<NutritionSummary> nutrition,
                       NutritionSummary total) {
    }

    private record Loaded(Optional<MealPlan> plan, Optional<PlanImpact> impact, List<Day> days,
                          Optional<UserProfile> profile) {
    }

    private final GuiContext context;
    private final DatePicker start = new DatePicker();
    private final Spinner<Integer> days = new Spinner<>(1, PlanRequest.MAX_DAYS, 7);
    private final ComboBox<String> mode = new ComboBox<>();
    private final TextField note = new TextField();
    private final TextField instruction = new TextField();
    private final TextField ingredient = new TextField();
    private final VBox result = new VBox();
    private final VBox impact = new VBox();
    private final Label planHeading = Ui.styled(new Label(), "card-title");
    private final Label planTargets = Ui.wrapped("", "muted");
    private final FlowPane dayCards = new FlowPane(14, 14);
    private final VBox planSection;
    private final VBox changeCard;
    private final VBox root;

    PlanView(GuiContext context) {
        this.context = context;
        mode.getItems().setAll("nutrition", "time", "budget");
        mode.setValue("nutrition");
        note.setPromptText("anything else, e.g. more fish");
        HBox.setHgrow(note, Priority.ALWAYS);
        instruction.setPromptText("e.g. make Wednesday's dinner vegetarian");
        HBox.setHgrow(instruction, Priority.ALWAYS);
        ingredient.setPromptText("ingredient to replace, e.g. milk");
        HBox.setHgrow(ingredient, Priority.ALWAYS);
        days.setEditable(true);
        days.setPrefWidth(80);

        Button generate = Ui.primary(new Button("Generate plan"));
        generate.setOnAction(e -> generate());
        Button propose = Ui.primary(new Button("Propose change"));
        propose.setOnAction(e -> change("The agent is preparing the change...",
                () -> context.facade().modifyPlan(context.profileId(), instruction.getText(), planningMode())));
        instruction.setOnAction(e -> propose.fire());
        Button substitute = new Button("Substitute");
        substitute.setOnAction(e -> change("Finding substitutes...",
                () -> context.facade().requestSubstitution(context.profileId(), ingredient.getText())));
        Button cheaper = Ui.ghost(new Button("Make it cheaper"));
        cheaper.setOnAction(e -> change("Looking for cheaper meals...",
                () -> context.facade().suggestCheaperPlan(context.profileId())));
        Button undo = Ui.ghost(new Button("Undo last change"));
        undo.setOnAction(e -> context.run("Undoing...", () -> context.facade().undoLastChange(context.profileId()),
                response -> {
                    showResult(response.status() == ResponseStatus.SUCCESS ? "success" : "warning",
                            response.rationale(), List.of(), List.of());
                    context.dataChanged();
                }));
        for (Button button : List.of(generate, propose, substitute, cheaper, undo)) {
            button.disableProperty().bind(context.busy());
        }

        VBox createCard = Ui.card("Create a new plan",
                Ui.row(8, new Label("Start"), start, new Label("Days"), days, new Label("Focus"), mode),
                Ui.row(8, note, generate),
                Ui.wrapped("The agent searches recipes, ranks them for your focus and builds the plan. It is checked "
                        + "against your allergies, diet, cooking times and budget, and saved automatically when "
                        + "every check passes. A new plan replaces the current one.", "muted"));

        changeCard = Ui.card("Change this plan",
                Ui.wrapped("Say what you want in your own words. You see the change before anything is applied.",
                        "muted"),
                Ui.row(8, instruction, propose),
                Ui.row(8, ingredient, substitute),
                Ui.row(8, cheaper, undo));

        planSection = new VBox(10, new VBox(2, planHeading, planTargets), dayCards);
        show(result, false);
        show(impact, false);
        root = Ui.page("Meal plan", "Your week of meals, built by the agent and checked by MealIQ.",
                result, impact, planSection, changeCard, createCard);
        context.onDataChanged(this::load);
    }

    Node view() {
        return Ui.scrolling(root);
    }

    private PlanningMode planningMode() {
        return switch (mode.getValue()) {
            case "time" -> PlanningMode.TIME_AWARE;
            case "budget" -> PlanningMode.BUDGET;
            default -> PlanningMode.NUTRITION_GOAL;
        };
    }

    void load() {
        UUID profileId = context.profileId();
        context.run("Loading plan...", () -> {
            Optional<MealPlan> plan = context.facade().getActivePlan(profileId);
            Optional<UserProfile> profile;
            try {
                profile = Optional.of(context.facade().getProfile(profileId));
            } catch (NotFoundException e) {
                profile = Optional.empty();
            }
            return new Loaded(plan, context.facade().pendingImpact(profileId),
                    plan.map(this::days).orElse(List.of()), profile);
        }, this::showPlan);
    }

    private List<Day> days(MealPlan plan) {
        List<Day> list = new ArrayList<>();
        for (Map.Entry<LocalDate, NutritionSummary> day : context.facade().dailyNutrition(plan).entrySet()) {
            List<PlannedMeal> meals = plan.mealsOn(day.getKey()).stream()
                    .sorted(Comparator.comparing(PlannedMeal::mealType)).toList();
            list.add(new Day(day.getKey(), meals, meals.stream()
                    .map(m -> context.facade().recipeNutrition(m.recipe(), m.servings())).toList(), day.getValue()));
        }
        return list;
    }

    /** Shows the plan as day cards, with any pending impact on top (the UML's showPlan). */
    void showPlan(Loaded loaded) {
        if (start.getValue() == null) {
            start.setValue(context.facade().today());
        }
        impact.getChildren().clear();
        loaded.impact().ifPresent(i -> {
            VBox banner = Ui.banner("warning", "Recent changes affect this plan", i.conflicts());
            Button adapt = Ui.primary(new Button("Let the agent adapt it"));
            adapt.disableProperty().bind(context.busy());
            adapt.setOnAction(e -> change("The agent is adapting the plan...",
                    () -> context.facade().adaptPlan(context.profileId())));
            banner.getChildren().add(adapt);
            impact.getChildren().add(banner);
        });
        show(impact, loaded.impact().isPresent());

        boolean hasPlan = loaded.plan().isPresent();
        show(changeCard, hasPlan);
        dayCards.getChildren().clear();
        if (!hasPlan) {
            planHeading.setText("No plan yet");
            planTargets.setText("Create one below, or ask the agent from Home.");
            return;
        }
        MealPlan plan = loaded.plan().get();
        planHeading.setText(plan.startDate().format(DAY) + " to " + plan.endDate().format(DAY) + "  ·  "
                + plan.days() + (plan.days() == 1 ? " day" : " days") + ", " + plan.meals().size() + " meals");
        planTargets.setText(loaded.profile().map(PlanView::targets).orElse("") + "x2 means two servings. "
                + "Click a recipe for its ingredients and steps. " + Ui.DISCLAIMER);
        loaded.days().forEach(day -> dayCards.getChildren().add(dayCard(day)));
    }

    private Node dayCard(Day day) {
        VBox card = Ui.card(day.date().format(DAY));
        card.getStyleClass().add("day-card");
        card.setPrefWidth(300);
        card.setMinWidth(260);
        for (int i = 0; i < day.meals().size(); i++) {
            PlannedMeal meal = day.meals().get(i);
            Hyperlink name = Ui.styled(new Hyperlink(meal.recipe().name()), "recipe-link");
            name.setWrapText(true);
            name.setMaxWidth(150);
            name.setOnAction(e -> context.showInfo(meal.recipe().name(),
                    Ui.describe(context.facade(), context.profileId(), meal.recipe())));
            HBox row = Ui.row(8, Ui.mealTag(meal.mealType()), name);
            if (meal.servings() > 1) {
                row.getChildren().add(Ui.styled(new Label("x" + meal.servings()), "portion"));
            }
            Label minutes = Ui.styled(new Label(meal.recipe().totalMinutes() + " min"), "muted");
            minutes.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().addAll(Ui.spacer(), minutes);
            card.getChildren().add(row);
        }
        card.getChildren().add(Ui.styled(new Label(Ui.kcal(day.total()) + " kcal  ·  " + Ui.protein(day.total())
                + " protein"), "day-total"));
        return card;
    }

    private static String targets(UserProfile p) {
        if (p.dailyCalorieTarget() == null && p.dailyProteinTargetG() == null) {
            return "";
        }
        return "Daily target: " + (p.dailyCalorieTarget() == null ? "-" : p.dailyCalorieTarget() + " kcal") + ", "
                + (p.dailyProteinTargetG() == null ? "-" : p.dailyProteinTargetG() + " g protein") + ". ";
    }

    private void generate() {
        PlanRequest request = new PlanRequest(context.profileId(), start.getValue() == null ? context.facade().today()
                : start.getValue(), days.getValue(), planningMode(), note.getText().isBlank() ? null : note.getText());
        context.run("The agent is planning: searching, ranking and checking (this can take a minute)...",
                () -> context.facade().generateWeeklyPlan(request), response -> {
                    switch (response.status()) {
                        case SUCCESS -> showResult("success", "Plan saved",
                                response.rationale().isEmpty() ? List.of() : List.of(response.rationale()),
                                details(response));
                        case NEEDS_CLARIFICATION -> showResult("warning", "The agent needs something first",
                                List.of(response.rationale()), List.of());
                        default -> showResult("error", "No plan was saved", List.of(response.rationale()
                                        + " The current plan, if any, is unchanged. Try fewer days, looser time "
                                        + "limits, or another focus."), details(response));
                    }
                    context.dataChanged();
                });
    }

    /** Notes and the agent's steps, shown folded under a result. */
    private static List<String> details(AgentResponse response) {
        List<String> lines = new ArrayList<>();
        response.conflicts().forEach(c -> lines.add("• " + c));
        if (!response.agentSteps().isEmpty()) {
            lines.add("Agent steps:");
            for (int i = 0; i < response.agentSteps().size(); i++) {
                lines.add((i + 1) + ". " + response.agentSteps().get(i));
            }
        }
        return lines;
    }

    private void showResult(String kind, String title, List<String> lines, List<String> details) {
        VBox banner = Ui.banner(kind, title, lines);
        if (!details.isEmpty()) {
            long notes = details.stream().filter(d -> d.startsWith("• ")).count();
            banner.getChildren().add(Ui.details("Details (" + notes + " note" + (notes == 1 ? "" : "s")
                    + ", how the agent worked)", details));
        }
        Button close = new Button("Dismiss");
        close.setOnAction(e -> show(result, false));
        banner.getChildren().add(close);
        result.getChildren().setAll(banner);
        show(result, true);
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /** Asks for a change and previews it; it is applied only if the user confirms (the UML's showChangePreview). */
    private void change(String message, Supplier<AgentResponse> request) {
        context.run(message, request, this::showChangePreview);
    }

    void showChangePreview(AgentResponse response) {
        if (response.status() != ResponseStatus.NEEDS_CONFIRMATION) {
            String kind = response.status() == ResponseStatus.SUCCESS ? "success" : "warning";
            String title = response.status() == ResponseStatus.NEEDS_CLARIFICATION ? "The agent has a question"
                    : response.status() == ResponseStatus.SUCCESS ? "Nothing to change" : "No change was made";
            showResult(kind, title, List.of(response.rationale()), details(response));
            return;
        }
        PlanChange change = response.change();
        StringBuilder preview = new StringBuilder();
        for (PlannedMeal proposed : change.proposal().meals()) {
            PlannedMeal current = response.plan().findMeal(proposed.id());
            preview.append(current.date().format(DAY)).append(' ')
                    .append(current.mealType().name().toLowerCase(Locale.ROOT))
                    .append(":\n  ").append(current.recipe().name()).append("  ->  ").append(proposed.recipe().name())
                    .append(" (").append(proposed.recipe().totalMinutes()).append(" min)\n");
        }
        if (!response.rationale().isEmpty()) {
            preview.append("\nWhy: ").append(response.rationale()).append('\n');
        }
        response.conflicts().forEach(c -> preview.append("Note: ").append(c).append('\n'));
        if (!context.confirm("Apply this change?", preview.toString())) {
            showResult("warning", "Cancelled", List.of("The plan is unchanged."), List.of());
            return;
        }
        UUID planId = response.plan().id();
        context.run("Applying...", () -> context.facade().executeChange(planId, change), applied -> {
            if (applied.status() == ResponseStatus.SUCCESS) {
                showResult("success", "Change applied", List.of(applied.rationale(),
                        "The shopping list was refreshed. You can undo this change."), List.of());
            } else {
                showResult("error", "The change was not applied", List.of(applied.rationale()), details(applied));
            }
            context.dataChanged();
        });
    }
}
