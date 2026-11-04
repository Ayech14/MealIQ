package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * What is at home (F02, UC02), with the impact on the plan after each change (F13). Names
 * can be typed the way people say them: the list filters as you type, a general name such as
 * "beef" asks which kind, and a food MealIQ does not know is handed to the agent.
 */
final class PantryView {

    private record Row(String ingredient, String quantity, String expiry) {
    }

    private record Loaded(Pantry pantry, Set<UUID> expired, List<String> catalogue) {
    }

    private final GuiContext context;
    private final TableView<Row> table = new TableView<>();
    private final ComboBox<String> ingredient = new ComboBox<>();
    private final Ui.Autocomplete names;
    private final TextField amount = new TextField();
    private final ComboBox<String> unit = new ComboBox<>();
    private final DatePicker expires = new DatePicker();
    private final VBox message = new VBox();
    private final VBox root;

    PantryView(GuiContext context) {
        this.context = context;
        table.getColumns().add(Ui.column("Ingredient", Row::ingredient, 260));
        table.getColumns().add(Ui.column("Quantity", Row::quantity, 140));
        table.getColumns().add(Ui.column("Expires", Row::expiry, 240));
        table.setPlaceholder(new Label("The pantry is empty. Add what you have at home above."));
        table.setPrefHeight(380);
        Ui.fill(table);
        VBox.setVgrow(table, Priority.ALWAYS);

        ingredient.setPromptText("ingredient, e.g. beef or eggs");
        ingredient.setPrefWidth(240);
        names = Ui.autocomplete(ingredient);
        amount.setPromptText("amount");
        amount.setPrefColumnCount(6);
        unit.getItems().setAll("g", "kg", "ml", "l", "unit", "cup", "tbsp", "tsp", "dozen");
        unit.setValue("g");
        expires.setPromptText("expires (optional)");
        Button save = Ui.primary(new Button("Add or update"));
        save.setOnAction(e -> save());
        amount.setOnAction(e -> save());
        Button remove = new Button("Remove selected");
        remove.setOnAction(e -> remove());
        save.disableProperty().bind(context.busy());
        remove.disableProperty().bind(context.busy());

        VBox add = Ui.card("Add or update an item",
                Ui.row(8, ingredient, amount, unit, expires, save),
                Ui.wrapped("Type a food the way you say it. The list narrows as you type; for a general name like "
                        + "\"beef\" MealIQ asks which kind, and the agent handles names it does not know. "
                        + "An amount of 0 keeps the item listed as out of stock.", "muted"));
        HBox actions = Ui.row(8, Ui.spacer(), remove);
        VBox list = Ui.card("In your pantry", table, actions);
        VBox.setVgrow(list, Priority.ALWAYS);
        root = Ui.page("Pantry", "What you have at home. The agent plans around it first and leaves it off the "
                + "shopping list.", add, message, list);
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

    private void show(Loaded loaded) {
        names.options(loaded.catalogue());
        table.getItems().setAll(loaded.pantry().items().values().stream()
                .sorted(Comparator.comparing((PantryItem i) -> i.ingredient().name()))
                .map(i -> new Row(i.ingredient().name(), i.quantity().toString(), i.expiryDate() == null ? ""
                        : i.expiryDate() + (loaded.expired().contains(i.ingredient().id())
                                ? "  (expired, not counted)" : "")))
                .toList());
    }

    private void save() {
        String typed = names.text();
        if (typed.isBlank()) {
            say("warning", "Type an ingredient first.", List.of());
            return;
        }
        double value;
        try {
            value = Double.parseDouble(amount.getText().strip());
        } catch (NumberFormatException e) {
            say("warning", "The amount must be a number, e.g. 450.", List.of());
            return;
        }
        LocalDate expiry = expires.getValue();
        String chosenUnit = unit.getValue();
        context.run("Looking up \"" + typed + "\"...", () -> context.facade().matchIngredients(typed).stream()
                .map(Ingredient::name).toList(), matches -> {
                    if (matches.isEmpty()) {
                        unknown(typed, value, chosenUnit);
                    } else if (matches.size() == 1) {
                        store(matches.getFirst(), value, chosenUnit, expiry, typed);
                    } else {
                        ChoiceDialog<String> which = new ChoiceDialog<>(matches.getFirst(), matches);
                        which.setTitle("Which kind?");
                        which.setHeaderText("Which kind of \"" + typed + "\" do you have?");
                        which.setContentText("Ingredient:");
                        Optional<String> picked = which.showAndWait();
                        picked.ifPresentOrElse(name -> store(name, value, chosenUnit, expiry, typed),
                                () -> say("warning", "Nothing was added.", List.of()));
                    }
                });
    }

    /** A name MealIQ does not know: offer to let the agent work out what it is. */
    private void unknown(String typed, double value, String chosenUnit) {
        VBox banner = Ui.banner("warning", "MealIQ does not know \"" + typed + "\"",
                List.of("The agent can match it to an ingredient MealIQ knows, or ask you about it."));
        Button ask = Ui.primary(new Button("Ask the agent"));
        ask.setOnAction(e -> context.askAgent("I have " + format(value) + " " + chosenUnit + " of " + typed));
        banner.getChildren().add(ask);
        message.getChildren().setAll(banner);
    }

    private void store(String name, double value, String chosenUnit, LocalDate expiry, String typed) {
        context.run("Saving...", () -> context.facade().updatePantry(context.profileId(),
                context.facade().pantryItem(name, value, chosenUnit, expiry)), response -> {
                    afterChange(response, name.equals(typed.strip().toLowerCase(Locale.ROOT)) ? null
                            : "Saved as " + name + ".");
                    names.clear();
                    amount.clear();
                    expires.setValue(null);
                });
    }

    private void remove() {
        Row selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            say("warning", "Select an item in the list to remove it.", List.of());
            return;
        }
        context.run("Removing...", () -> {
            context.facade().removePantryItem(context.profileId(), selected.ingredient());
            return AgentResponse.builder(ResponseStatus.SUCCESS)
                    .rationale("Removed " + selected.ingredient() + ".")
                    .conflicts(context.facade().pendingImpact(context.profileId())
                            .map(i -> i.conflicts()).orElse(List.of()))
                    .build();
        }, response -> afterChange(response, null));
    }

    private void afterChange(AgentResponse response, String note) {
        List<String> lines = note == null ? List.of() : List.of(note);
        say("success", response.rationale(), lines);
        if (!response.conflicts().isEmpty()) {
            VBox impact = Ui.banner("warning", "This affects your meal plan", response.conflicts());
            Button adapt = Ui.primary(new Button("Let the agent adapt the plan"));
            adapt.setOnAction(e -> context.askAgent("Adapt my plan to my recent changes"));
            impact.getChildren().add(adapt);
            message.getChildren().add(impact);
        }
        context.dataChanged();
    }

    private void say(String kind, String title, List<String> lines) {
        message.getChildren().setAll(Ui.banner(kind, title, lines));
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
