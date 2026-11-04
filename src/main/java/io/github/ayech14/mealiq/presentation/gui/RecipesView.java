package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Browse, ask the agent for, or generate recipes (F03, F04), with full details (F06). */
final class RecipesView {

    private record Row(Recipe recipe, String minutes, String kcal, String protein, String cost) {
    }

    private final GuiContext context;
    private final ComboBox<String> meal = new ComboBox<>();
    private final TextField maxMinutes = new TextField();
    private final TextField include = new TextField();
    private final TextField exclude = new TextField();
    private final ComboBox<String> rank = new ComboBox<>();
    private final CheckBox generated = new CheckBox("include AI-generated");
    private final TextField request = new TextField();
    private final TableView<Row> table = new TableView<>();
    private final TextArea details = GuiContext.readOnly("");
    private final Label message = new Label();
    private final VBox root = new VBox(10);

    RecipesView(GuiContext context) {
        this.context = context;
        meal.getItems().setAll("any", "breakfast", "lunch", "dinner", "snack");
        meal.setValue("any");
        maxMinutes.setPromptText("max min");
        maxMinutes.setPrefColumnCount(5);
        include.setPromptText("use any of (comma-separated)");
        exclude.setPromptText("without");
        rank.getItems().setAll("nutrition", "time", "budget");
        rank.setValue("nutrition");
        request.setPromptText("Describe what you want, e.g. quick high-protein dinner");
        HBox.setHgrow(request, Priority.ALWAYS);

        Button browse = new Button("Browse");
        browse.setOnAction(e -> browse());
        Button ask = new Button("Ask the agent");
        ask.setOnAction(e -> ask());
        Button generate = new Button("Generate a new recipe");
        generate.setOnAction(e -> generate());
        for (Button button : List.of(browse, ask, generate)) {
            button.disableProperty().bind(context.busy());
        }

        table.getColumns().add(Ui.column("Recipe", r -> r.recipe().name(), 280));
        table.getColumns().add(Ui.column("min", Row::minutes, 60));
        table.getColumns().add(Ui.column("kcal", Row::kcal, 70));
        table.getColumns().add(Ui.column("protein", Row::protein, 80));
        table.getColumns().add(Ui.column("cost", Row::cost, 80));
        table.setPlaceholder(new Label("Browse, or ask the agent."));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> {
            if (row != null) {
                UUID profileId = context.profileId();
                context.run("Loading recipe...", () -> Ui.describe(context.facade(), profileId, row.recipe()),
                        details::setText);
            }
        });
        SplitPane split = new SplitPane(table, details);
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);
        message.setWrapText(true);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(
                Ui.row(8, new Label("Meal"), meal, maxMinutes, include, exclude, new Label("Rank by"), rank,
                        generated, browse),
                Ui.row(8, request, ask, generate), message, split);
    }

    Node view() {
        return root;
    }

    private RecipeQuery query(boolean allowGenerated) {
        MealType type = "any".equals(meal.getValue()) ? null : MealType.valueOf(meal.getValue().toUpperCase(Locale.ROOT));
        Integer minutes = maxMinutes.getText().isBlank() ? null : Integer.valueOf(maxMinutes.getText().strip());
        return new RecipeQuery(type, minutes, split(include), split(exclude), null, allowGenerated);
    }

    private PlanningMode mode() {
        return switch (rank.getValue()) {
            case "time" -> PlanningMode.TIME_AWARE;
            case "budget" -> PlanningMode.BUDGET;
            default -> PlanningMode.NUTRITION_GOAL;
        };
    }

    void browse() {
        RecipeQuery query;
        try {
            query = query(generated.isSelected());
        } catch (NumberFormatException e) {
            message.setText("Max minutes must be a whole number.");
            return;
        }
        UUID profileId = context.profileId();
        PlanningMode mode = mode();
        context.run("Searching...", () -> rows(context.facade().browseRecipes(profileId, query, mode)), result -> {
            table.getItems().setAll(result.rows());
            message.setText(result.message());
        });
    }

    private void ask() {
        if (request.getText().isBlank()) {
            message.setText("Describe what you want first.");
            return;
        }
        RecipeQuery filters = query(false);
        String text = request.getText();
        UUID profileId = context.profileId();
        PlanningMode mode = mode();
        context.run("The agent is searching and ranking...",
                () -> rows(context.facade().recommendRecipe(profileId, text, filters, mode)), result -> {
                    table.getItems().setAll(result.rows());
                    message.setText(result.message());
                    if (!result.rows().isEmpty()) {
                        table.getSelectionModel().selectFirst();
                    }
                });
    }

    private void generate() {
        RecipeQuery filters;
        try {
            filters = query(true);
        } catch (NumberFormatException e) {
            message.setText("Max minutes must be a whole number.");
            return;
        }
        String text = request.getText();
        UUID profileId = context.profileId();
        context.run("The AI is writing a recipe...", () -> context.facade().generateRecipe(profileId, text, filters),
                response -> {
                    if (response.status() != ResponseStatus.NEEDS_CONFIRMATION) {
                        message.setText(response.rationale() + "\n  " + String.join("\n  ", response.conflicts()));
                        return;
                    }
                    Recipe recipe = response.recipes().getFirst();
                    String preview = Ui.describe(context.facade(), profileId, recipe) + "\n\n" + response.rationale();
                    if (!context.confirm("Save this generated recipe?", preview)) {
                        message.setText("Not saved.");
                        return;
                    }
                    context.run("Saving...", () -> context.facade().saveGeneratedRecipe(recipe.id()),
                            saved -> message.setText(saved.rationale()));
                });
    }

    private record Result(List<Row> rows, String message) {
    }

    private Result rows(AgentResponse response) {
        UUID profileId = context.profileId();
        List<Row> rows = response.recipes().stream().map(r -> {
            NutritionSummary n = context.facade().recipeNutrition(r, 1);
            return new Row(r, String.valueOf(r.totalMinutes()), Ui.kcal(n), Ui.protein(n),
                    context.facade().recipeCost(profileId, r).toString());
        }).toList();
        StringBuilder text = new StringBuilder();
        if (!response.rationale().isEmpty()) {
            text.append(response.status() == ResponseStatus.SUCCESS && !response.agentSteps().isEmpty()
                    ? "Why: " : "").append(response.rationale());
        }
        if (!response.conflicts().isEmpty()) {
            text.append(text.isEmpty() ? "" : "\n").append("Left out or noted: ")
                    .append(String.join(" ", response.conflicts()));
        }
        if (!response.agentSteps().isEmpty()) {
            text.append("\nAgent steps: ").append(response.agentSteps().size()).append(" tool call(s).");
        }
        return new Result(rows, text.toString());
    }

    private static List<String> split(TextField field) {
        return Arrays.stream(field.getText().split(",")).map(String::strip).filter(v -> !v.isEmpty()).toList();
    }
}
