package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.application.MealPlanningFacade;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.NutritionSummary;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.MealType;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Small helpers shared by the screens: page layout, cards, banners, table columns and recipe text. */
final class Ui {

    static final String DISCLAIMER = "Values come from USDA FoodData Central. A planning aid, not dietary advice.";

    private Ui() {
    }

    /** A horizontal row whose controls and labels line up on their centre. */
    static HBox row(double spacing, Node... nodes) {
        HBox row = new HBox(spacing, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        for (Node node : nodes) {
            if (node instanceof Labeled labeled && !labeled.isWrapText()) {
                labeled.setMinWidth(Region.USE_PREF_SIZE); // labels and buttons never shrink to "..."
            }
        }
        return row;
    }

    /** A page with a title and a one-line explanation above its content. */
    static VBox page(String title, String subtitle, Node... content) {
        Label heading = styled(new Label(title), "page-title");
        VBox page = new VBox(new VBox(2, heading, wrapped(subtitle, "page-subtitle")));
        page.getStyleClass().add("page");
        page.getChildren().addAll(content);
        return page;
    }

    /** A page that scrolls when its content is taller than the window. */
    static ScrollPane scrolling(VBox page) {
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        return scroll;
    }

    /** A white card; a null title leaves the heading out. */
    static VBox card(String title, Node... content) {
        VBox card = new VBox();
        card.getStyleClass().add("card");
        if (title != null) {
            card.getChildren().add(styled(new Label(title), "card-title"));
        }
        card.getChildren().addAll(content);
        return card;
    }

    /** A coloured message box: kind is success, warning or error. */
    static VBox banner(String kind, String title, List<String> lines) {
        VBox banner = new VBox(styled(wrapped(title), "banner-title"));
        banner.getStyleClass().addAll("banner", kind);
        lines.forEach(line -> banner.getChildren().add(wrapped(line)));
        return banner;
    }

    /** Collapsed details under a result, such as the agent's tool calls. */
    static TitledPane details(String title, List<String> lines) {
        VBox body = new VBox(4);
        lines.forEach(line -> body.getChildren().add(wrapped(line)));
        TitledPane pane = new TitledPane(title, body);
        pane.setExpanded(false);
        pane.setAnimated(false);
        return pane;
    }

    static Button primary(Button button) {
        button.getStyleClass().add("primary");
        return button;
    }

    static Button ghost(Button button) {
        button.getStyleClass().add("ghost");
        return button;
    }

    /** A coloured tag for a meal type: "Breakfast", "Lunch" ... */
    static Label mealTag(MealType type) {
        String name = type.name().toLowerCase(Locale.ROOT);
        Label tag = new Label(Character.toUpperCase(name.charAt(0)) + name.substring(1));
        tag.getStyleClass().addAll("meal-tag", name);
        return tag;
    }

    /** A label that wraps to the width it is given and grows to show every line. */
    static Label wrapped(String text, String... styles) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().addAll(styles);
        return label;
    }

    static <T extends Node> T styled(T node, String... styles) {
        node.getStyleClass().addAll(styles);
        return node;
    }

    /** A space that takes the free width of a row, pushing what follows to the right. */
    static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** Makes an editable combo box narrow its list to the names containing every typed word. */
    static Autocomplete autocomplete(ComboBox<String> box) {
        return new Autocomplete(box);
    }

    /** The filtering behind {@link #autocomplete}: the list shows only names that contain every typed word. */
    static final class Autocomplete {
        private final ComboBox<String> box;
        private final ObservableList<String> all = FXCollections.observableArrayList();
        private final FilteredList<String> shown = new FilteredList<>(all, name -> true);
        private boolean picking;

        private Autocomplete(ComboBox<String> box) {
            this.box = box;
            box.setEditable(true);
            box.setItems(shown);
            box.setVisibleRowCount(8);
            box.getSelectionModel().selectedItemProperty().addListener((obs, old, picked) -> {
                if (picked != null) {
                    picking = true;
                    box.getEditor().setText(picked);
                    picking = false;
                }
            });
            box.getEditor().textProperty().addListener((obs, old, text) -> {
                if (!picking) {
                    Platform.runLater(() -> filter(text));
                }
            });
        }

        void options(List<String> names) {
            if (all.isEmpty()) {
                all.setAll(names);
            }
        }

        /** What the user typed or picked. */
        String text() {
            String text = box.getEditor().getText();
            return text == null ? "" : text.strip();
        }

        void clear() {
            picking = true;
            box.getSelectionModel().clearSelection();
            box.getEditor().clear();
            shown.setPredicate(name -> true);
            picking = false;
        }

        /** The names now offered; also used by tests. */
        List<String> shown() {
            return List.copyOf(shown);
        }

        void filter(String text) {
            String[] words = text == null ? new String[0] : text.strip().toLowerCase(Locale.ROOT).split("\\s+");
            shown.setPredicate(name -> Arrays.stream(words).allMatch(name::contains));
            if (box.getEditor().isFocused() && !shown.isEmpty() && !text.isBlank()) {
                box.show();
            } else if (shown.isEmpty()) {
                box.hide();
            }
        }
    }

    static <R> TableColumn<R, String> column(String title, Function<R, String> value, double width) {
        TableColumn<R, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    /** Columns share the table's width, so no empty column is left on the right. */
    static <R> TableView<R> fill(TableView<R> table) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return table;
    }

    static String kcal(NutritionSummary n) {
        return n.complete() ? String.valueOf(Math.round(n.calories())) : "?";
    }

    static String protein(NutritionSummary n) {
        return n.complete() ? Math.round(n.proteinG()) + " g" : "?";
    }

    /** Everything about a recipe for one serving: ingredients, steps, nutrition, cost and profile notes. */
    static String describe(MealPlanningFacade facade, UUID profileId, Recipe recipe) {
        StringBuilder text = new StringBuilder(recipe.name()).append('\n');
        text.append(String.join(", ", recipe.mealTypes().stream().sorted()
                        .map(t -> t.name().toLowerCase(Locale.ROOT)).toList()))
                .append(" | ").append(recipe.prepMinutes()).append(" min prep + ").append(recipe.cookMinutes())
                .append(" min cooking | makes ").append(recipe.servings()).append(" serving(s)\n");
        if (!recipe.tags().isEmpty()) {
            text.append("Tags: ").append(String.join(", ", recipe.tags().stream().sorted().toList())).append('\n');
        }
        text.append("\nIngredients:\n");
        for (IngredientRequirement line : recipe.ingredients()) {
            text.append("  ").append(line.quantity()).append(' ').append(line.ingredient().name())
                    .append(line.optional() ? " (optional)" : "").append('\n');
        }
        text.append("\nSteps:\n");
        for (int i = 0; i < recipe.instructions().size(); i++) {
            text.append("  ").append(i + 1).append(". ").append(recipe.instructions().get(i)).append('\n');
        }
        NutritionSummary n = facade.recipeNutrition(recipe, 1);
        text.append("\nPer serving: ").append(n.complete() ? String.format(Locale.ROOT,
                "%d kcal, %.0f g protein, %.0f g carbs, %.0f g fat", Math.round(n.calories()), n.proteinG(),
                n.carbsG(), n.fatG()) : "nutrition incomplete").append('\n');
        text.append("Cost per serving beyond your pantry: ").append(facade.recipeCost(profileId, recipe)).append('\n');
        ValidationResult check = facade.checkRecipe(profileId, recipe);
        check.violations().forEach(v -> text.append("Not suitable: ").append(v).append('\n'));
        check.warnings().forEach(w -> text.append("Note: ").append(w).append('\n'));
        return text.append('\n').append(DISCLAIMER).toString();
    }
}
