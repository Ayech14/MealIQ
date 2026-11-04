package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** What is at home (F02, UC02), with the impact on the plan after each change (F13). */
final class PantryView {

    private record Row(String ingredient, String quantity, String expiry) {
    }

    private final GuiContext context;
    private final TableView<Row> table = new TableView<>();
    private final ComboBox<String> ingredient = new ComboBox<>();
    private final TextField amount = new TextField();
    private final ComboBox<String> unit = new ComboBox<>();
    private final DatePicker expires = new DatePicker();
    private final Label message = new Label();
    private final VBox root = new VBox(10);

    PantryView(GuiContext context) {
        this.context = context;
        table.getColumns().add(Ui.column("Ingredient", Row::ingredient, 220));
        table.getColumns().add(Ui.column("Quantity", Row::quantity, 120));
        table.getColumns().add(Ui.column("Expires", Row::expiry, 220));
        table.setPlaceholder(new Label("The pantry is empty."));
        VBox.setVgrow(table, Priority.ALWAYS);

        ingredient.setEditable(true);
        ingredient.setPromptText("ingredient");
        ingredient.setPrefWidth(200);
        amount.setPromptText("amount");
        amount.setPrefColumnCount(6);
        unit.getItems().setAll("g", "kg", "ml", "l", "unit", "cup", "tbsp", "tsp", "dozen");
        unit.setValue("g");
        expires.setPromptText("expires (optional)");
        Button save = new Button("Add or update");
        save.setOnAction(e -> save());
        Button remove = new Button("Remove selected");
        remove.setOnAction(e -> remove());
        save.disableProperty().bind(context.busy());
        remove.disableProperty().bind(context.busy());
        message.setWrapText(true);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(table, Ui.row(8, ingredient, amount, unit, expires, save, remove), message);
        context.onDataChanged(this::load);
    }

    Node view() {
        return root;
    }

    void load() {
        UUID profileId = context.profileId();
        context.run("Loading pantry...", () -> new Loaded(context.facade().getPantry(profileId),
                context.facade().expiredPantryItems(profileId).stream().map(i -> i.ingredient().id())
                        .collect(Collectors.toSet()),
                context.facade().searchIngredients("").stream().map(Ingredient::name).toList()), this::show);
    }

    private record Loaded(Pantry pantry, Set<UUID> expired, List<String> catalogue) {
    }

    private void show(Loaded loaded) {
        if (ingredient.getItems().isEmpty()) {
            ingredient.getItems().setAll(loaded.catalogue());
        }
        table.getItems().setAll(loaded.pantry().items().values().stream()
                .sorted(Comparator.comparing((PantryItem i) -> i.ingredient().name()))
                .map(i -> new Row(i.ingredient().name(), i.quantity().toString(), i.expiryDate() == null ? ""
                        : i.expiryDate() + (loaded.expired().contains(i.ingredient().id())
                                ? "  (expired, not counted)" : "")))
                .toList());
    }

    private void save() {
        String name = ingredient.getEditor().getText();
        double value;
        try {
            value = Double.parseDouble(amount.getText().strip());
        } catch (NumberFormatException e) {
            message.setText("Amount must be a number.");
            return;
        }
        LocalDate expiry = expires.getValue();
        String chosenUnit = unit.getValue();
        context.run("Saving...", () -> context.facade().updatePantry(context.profileId(),
                context.facade().pantryItem(name, value, chosenUnit, expiry)), this::afterChange);
    }

    private void remove() {
        Row selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            message.setText("Select an item to remove.");
            return;
        }
        context.run("Removing...", () -> {
            context.facade().removePantryItem(context.profileId(), selected.ingredient());
            return AgentResponse.builder(ResponseStatus.SUCCESS)
                    .rationale("Removed " + selected.ingredient() + ".")
                    .conflicts(context.facade().pendingImpact(context.profileId())
                            .map(i -> i.conflicts()).orElse(List.of()))
                    .build();
        }, this::afterChange);
    }

    private void afterChange(AgentResponse response) {
        message.setText(response.rationale() + (response.conflicts().isEmpty() ? ""
                : "\nThis affects your meal plan:\n  " + String.join("\n  ", response.conflicts())
                        + "\nOpen the Plan tab and choose Adapt."));
        context.dataChanged();
    }
}
