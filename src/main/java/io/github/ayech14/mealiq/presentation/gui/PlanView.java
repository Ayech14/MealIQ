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
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The weekly plan (F05, F06) and every way to change it: typed changes, substitutions,
 * cheaper meals, adaptation and undo (F08 to F11, F13). Each change is previewed and applied
 * only after the user confirms (D03).
 */
final class PlanView {

    private record Row(String day, String meal, String recipe, String minutes, String kcal, String protein) {
    }

    private record Loaded(Optional<MealPlan> plan, Optional<PlanImpact> impact, List<Row> rows) {
    }

    private final GuiContext context;
    private final DatePicker start = new DatePicker();
    private final Spinner<Integer> days = new Spinner<>(1, PlanRequest.MAX_DAYS, 7);
    private final ComboBox<String> mode = new ComboBox<>();
    private final TextField note = new TextField();
    private final TextField instruction = new TextField();
    private final TextField ingredient = new TextField();
    private final TableView<Row> table = new TableView<>();
    private final TextArea notes = GuiContext.readOnly("");
    private final Label impactBanner = new Label();
    private final Button adapt = new Button("Adapt plan");
    private final VBox root = new VBox(10);

    PlanView(GuiContext context) {
        this.context = context;
        mode.getItems().setAll("nutrition", "time", "budget");
        mode.setValue("nutrition");
        note.setPromptText("optional note, e.g. more fish");
        instruction.setPromptText("Change in your own words, e.g. make Wednesday's dinner vegetarian");
        ingredient.setPromptText("ingredient to replace");
        HBox.setHgrow(instruction, Priority.ALWAYS);
        days.setEditable(true);

        Button generate = new Button("Generate plan");
        generate.setOnAction(e -> generate());
        Button propose = new Button("Propose change");
        propose.setOnAction(e -> change("The agent is preparing the change...",
                () -> context.facade().modifyPlan(context.profileId(), instruction.getText(), planningMode())));
        Button substitute = new Button("Substitute");
        substitute.setOnAction(e -> change("Finding substitutes...",
                () -> context.facade().requestSubstitution(context.profileId(), ingredient.getText())));
        Button cheaper = new Button("Make cheaper");
        cheaper.setOnAction(e -> change("Looking for cheaper meals...",
                () -> context.facade().suggestCheaperPlan(context.profileId())));
        adapt.setOnAction(e -> change("The agent is adapting the plan...",
                () -> context.facade().adaptPlan(context.profileId())));
        Button undo = new Button("Undo last change");
        undo.setOnAction(e -> context.run("Undoing...", () -> context.facade().undoLastChange(context.profileId()),
                response -> {
                    notes.setText(response.rationale());
                    context.dataChanged();
                }));
        for (Button button : List.of(generate, propose, substitute, cheaper, adapt, undo)) {
            button.disableProperty().bind(context.busy());
        }

        table.getColumns().add(Ui.column("Day", Row::day, 130));
        table.getColumns().add(Ui.column("Meal", Row::meal, 90));
        table.getColumns().add(Ui.column("Recipe", Row::recipe, 300));
        table.getColumns().add(Ui.column("min", Row::minutes, 60));
        table.getColumns().add(Ui.column("kcal", Row::kcal, 70));
        table.getColumns().add(Ui.column("protein", Row::protein, 140));
        table.setPlaceholder(new Label("No plan yet. Choose a start date and generate one."));
        SplitPane split = new SplitPane(table, notes);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.7);
        VBox.setVgrow(split, Priority.ALWAYS);
        impactBanner.setWrapText(true);
        impactBanner.setMaxWidth(Double.MAX_VALUE);
        impactBanner.setMinHeight(Region.USE_PREF_SIZE);
        impactBanner.setStyle("-fx-background-color: #fff3cd; -fx-padding: 8;");
        impactBanner.setManaged(false);
        impactBanner.setVisible(false);

        root.setPadding(new Insets(16));
        root.getChildren().addAll(
                Ui.row(8, new Label("Start"), start, new Label("Days"), days, new Label("Mode"), mode, note, generate),
                impactBanner,
                Ui.row(8, instruction, propose),
                Ui.row(8, ingredient, substitute, cheaper, adapt, undo),
                split);
        context.onDataChanged(this::load);
    }

    Node view() {
        return root;
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
            return new Loaded(plan, context.facade().pendingImpact(profileId),
                    plan.map(p -> rows(p, context.facade().dailyNutrition(p), targets(profileId))).orElse(List.of()));
        }, this::showPlan);
    }

    /** "target 2000 kcal, 120 g" from the profile, or empty without targets. */
    private String targets(UUID profileId) {
        try {
            UserProfile p = context.facade().getProfile(profileId);
            if (p.dailyCalorieTarget() == null && p.dailyProteinTargetG() == null) {
                return "";
            }
            return "target " + (p.dailyCalorieTarget() == null ? "-" : p.dailyCalorieTarget()) + " kcal, "
                    + (p.dailyProteinTargetG() == null ? "-" : p.dailyProteinTargetG() + " g");
        } catch (NotFoundException e) {
            return "";
        }
    }

    private List<Row> rows(MealPlan plan, Map<LocalDate, NutritionSummary> daily, String targets) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, NutritionSummary> day : daily.entrySet()) {
            LocalDate date = day.getKey();
            String label = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + date;
            for (PlannedMeal meal : plan.mealsOn(date).stream().sorted(Comparator.comparing(PlannedMeal::mealType))
                    .toList()) {
                NutritionSummary n = context.facade().recipeNutrition(meal.recipe(), meal.servings());
                rows.add(new Row(label, meal.mealType().name().toLowerCase(Locale.ROOT), meal.recipe().name(),
                        String.valueOf(meal.recipe().totalMinutes()), Ui.kcal(n), Ui.protein(n)));
                label = "";
            }
            rows.add(new Row("", "", "Day total" + (targets.isEmpty() ? "" : " (" + targets + ")"), "",
                    Ui.kcal(day.getValue()), Ui.protein(day.getValue())));
        }
        return rows;
    }

    /** Shows the plan, its daily totals and any pending impact (the UML's showPlan). */
    void showPlan(Loaded loaded) {
        table.getItems().setAll(loaded.rows());
        if (start.getValue() == null) {
            start.setValue(LocalDate.now());
        }
        boolean hasImpact = loaded.impact().isPresent();
        impactBanner.setVisible(hasImpact);
        impactBanner.setManaged(hasImpact);
        adapt.setStyle(hasImpact ? "-fx-font-weight: bold;" : "");
        loaded.impact().ifPresent(i -> impactBanner.setText("Recent changes affect this plan:\n  "
                + String.join("\n  ", i.conflicts()) + "\nChoose Adapt plan to fix only these meals."));
        if (loaded.plan().isPresent() && notes.getText().isEmpty()) {
            notes.setText(Ui.DISCLAIMER);
        }
    }

    private void generate() {
        PlanRequest request = new PlanRequest(context.profileId(), start.getValue() == null ? LocalDate.now()
                : start.getValue(), days.getValue(), planningMode(), note.getText().isBlank() ? null : note.getText());
        context.run("The agent is planning (this can take a minute)...",
                () -> context.facade().generateWeeklyPlan(request), response -> {
                    notes.setText(summary(response, response.status() == ResponseStatus.SUCCESS
                            ? "Plan saved." : response.rationale()));
                    context.dataChanged();
                });
    }

    /** Asks for a change and previews it; it is applied only if the user confirms (the UML's showChangePreview). */
    private void change(String message, Supplier<AgentResponse> request) {
        context.run(message, request, this::showChangePreview);
    }

    void showChangePreview(AgentResponse response) {
        if (response.status() != ResponseStatus.NEEDS_CONFIRMATION) {
            String prefix = response.status() == ResponseStatus.NEEDS_CLARIFICATION ? "Question: " : "";
            notes.setText(summary(response, prefix + response.rationale()));
            return;
        }
        PlanChange change = response.change();
        StringBuilder preview = new StringBuilder();
        for (PlannedMeal proposed : change.proposal().meals()) {
            PlannedMeal current = response.plan().findMeal(proposed.id());
            preview.append(current.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).append(' ')
                    .append(current.date()).append(' ').append(current.mealType().name().toLowerCase(Locale.ROOT))
                    .append(":\n  ").append(current.recipe().name()).append("  ->  ").append(proposed.recipe().name())
                    .append(" (").append(proposed.recipe().totalMinutes()).append(" min)\n");
        }
        if (!response.rationale().isEmpty()) {
            preview.append("\nWhy: ").append(response.rationale()).append('\n');
        }
        response.conflicts().forEach(c -> preview.append("Note: ").append(c).append('\n'));
        if (!context.confirm("Apply this change?", preview.toString())) {
            notes.setText("Cancelled. The plan is unchanged.");
            return;
        }
        UUID planId = response.plan().id();
        context.run("Applying...", () -> context.facade().executeChange(planId, change), applied -> {
            notes.setText(applied.status() == ResponseStatus.SUCCESS
                    ? "Applied: " + applied.rationale() + "\nThe grocery list was refreshed. Undo is available."
                    : applied.rationale());
            context.dataChanged();
        });
    }

    private static String summary(AgentResponse response, String headline) {
        StringBuilder text = new StringBuilder(headline);
        if (response.status() == ResponseStatus.SUCCESS && !response.rationale().isEmpty()
                && !headline.contains(response.rationale())) {
            text.append("\nWhy: ").append(response.rationale());
        }
        response.conflicts().forEach(c -> text.append("\n- ").append(c));
        if (!response.agentSteps().isEmpty()) {
            text.append("\n\nAgent steps:");
            for (int i = 0; i < response.agentSteps().size(); i++) {
                text.append("\n").append(i + 1).append(". ").append(response.agentSteps().get(i));
            }
        }
        return text.toString();
    }
}
