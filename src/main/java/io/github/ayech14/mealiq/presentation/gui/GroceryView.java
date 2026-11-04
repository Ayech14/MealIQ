package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.GroceryList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** The priced shopping list for the active plan, compared with the budget (F07, F08, F12). */
final class GroceryView {

    private record Row(String category, String ingredient, String need, String have, String buy, String price) {
    }

    private final GuiContext context;
    private final TableView<Row> table = new TableView<>();
    private final Label summary = new Label();
    private final VBox root = new VBox(10);

    GroceryView(GuiContext context) {
        this.context = context;
        table.getColumns().add(Ui.column("Category", Row::category, 110));
        table.getColumns().add(Ui.column("Ingredient", Row::ingredient, 200));
        table.getColumns().add(Ui.column("need", Row::need, 90));
        table.getColumns().add(Ui.column("have", Row::have, 90));
        table.getColumns().add(Ui.column("buy", Row::buy, 90));
        table.getColumns().add(Ui.column("price", Row::price, 80));
        table.setPlaceholder(new Label("No shopping list yet. Generate a plan, then refresh the list."));
        Ui.fill(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        Button refresh = Ui.primary(new Button("Refresh list and check budget"));
        refresh.disableProperty().bind(context.busy());
        refresh.setOnAction(e -> refresh());
        summary.setWrapText(true);
        root.getStyleClass().add("page");
        root.getChildren().addAll(Ui.styled(new Label("Groceries"), "page-title"),
                Ui.wrapped("What to buy for your plan beyond what is in your pantry, priced with Statistics Canada "
                        + "average retail prices. Unpriced items are counted, not guessed.", "page-subtitle"),
                Ui.row(8, refresh), table, summary);
        context.onDataChanged(this::load);
    }

    Node view() {
        return root;
    }

    void load() {
        UUID profileId = context.profileId();
        context.run("Loading shopping list...", () -> context.facade().getGroceryList(profileId), this::showSaved);
    }

    private void showSaved(Optional<GroceryList> list) {
        if (list.isEmpty()) {
            table.getItems().clear();
            summary.setText("");
            return;
        }
        showGroceryList(list.get(), null, List.of());
        if (list.get().isStale()) {
            summary.setText("This list is out of date: your plan or pantry changed. Refresh it.\n" + summary.getText());
        }
    }

    private void refresh() {
        UUID profileId = context.profileId();
        context.run("Pricing the list...", () -> context.facade().generateOptimizedGroceryList(profileId),
                (AgentResponse response) -> showGroceryList(response.groceryList(), response.budgetStatus(),
                        response.conflicts()));
    }

    /** Shows the list with its estimate and budget status (the UML's showGroceryList). */
    void showGroceryList(GroceryList list, BudgetStatus status, List<String> notes) {
        table.getItems().setAll(list.items().stream().map(i -> new Row(i.ingredient().category(),
                i.ingredient().name(), i.required().toString(), i.inPantry().isZero() ? "-" : i.inPantry().toString(),
                i.toBuy().isZero() ? "-" : i.toBuy().toString(),
                i.toBuy().isZero() ? "-" : i.estimatedPrice() == null ? "?" : "$" + i.estimatedPrice())).toList());
        List<GroceryItem> toBuy = list.items().stream().filter(i -> !i.toBuy().isZero()).toList();
        long unpriced = toBuy.stream().filter(i -> i.estimatedPrice() == null).count();
        StringBuilder text = new StringBuilder(toBuy.size() + " of " + list.items().size()
                + " ingredients to buy. Estimated cost: " + (unpriced == 0 ? "$" + list.total()
                        : "at least $" + list.total() + " (" + unpriced + " without a price)") + ".");
        if (status != null) {
            text.append("\nBudget for this plan: $").append(status.budget()).append(status.withinBudget()
                    ? (status.partial() ? " - the priced items fit." : " - within budget.")
                    : " - over by $" + status.shortfall() + ". Use Make cheaper on the Plan tab.");
        }
        notes.forEach(n -> text.append("\n").append(n));
        text.append("\nPrices: ").append(context.facade().priceSource()).append('.');
        summary.setText(text.toString());
    }
}
