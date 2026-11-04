package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.application.MealPlanningFacade;
import io.github.ayech14.mealiq.config.AppConfig;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/**
 * The desktop interface: one tab per area, all calling {@link MealPlanningFacade} like the
 * CLI does, so both give the same results (D02). Start it with {@code ./mealiq-gui}.
 */
public final class MealPlanningGUI extends Application {

    private GuiContext context;

    @Override
    public void start(Stage stage) {
        context = new GuiContext(MealPlanningFacade.fromConfig(AppConfig.load()));
        stage.setTitle("MealIQ");
        stage.setScene(new Scene(build(context), 1100, 760));
        stage.setOnCloseRequest(e -> context.shutdown());
        stage.show();
        context.dataChanged();
    }

    /** The whole window content for a context; also used to render the screens in tests. */
    static BorderPane build(GuiContext context) {
        ProfileView profile = new ProfileView(context);
        PantryView pantry = new PantryView(context);
        RecipesView recipes = new RecipesView(context);
        PlanView plan = new PlanView(context);
        GroceryView grocery = new GroceryView(context);
        TabPane tabs = new TabPane(tab("Profile", profile.view()), tab("Pantry", pantry.view()),
                tab("Recipes", recipes.view()), tab("Plan", plan.view()), tab("Groceries", grocery.view()));

        TextField profileName = new TextField(context.profileName());
        profileName.setPrefColumnCount(10);
        Button switchProfile = new Button("Switch");
        switchProfile.setOnAction(e -> context.profileName(profileName.getText()));
        switchProfile.disableProperty().bind(context.busy());
        Label title = new Label("MealIQ");
        title.setStyle("-fx-font-size: 18; -fx-font-weight: bold;");
        HBox header = Ui.row(10, title, new Label("Profile:"), profileName, switchProfile);
        header.setPadding(new Insets(10, 16, 6, 16));

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);
        spinner.visibleProperty().bind(context.busy());
        Label status = new Label();
        status.textProperty().bind(context.status());
        HBox footer = Ui.row(8, spinner, status);
        footer.setPadding(new Insets(6, 16, 8, 16));

        BorderPane root = new BorderPane(tabs);
        root.setTop(header);
        root.setBottom(footer);
        return root;
    }

    private static Tab tab(String name, Node content) {
        Tab tab = new Tab(name, content);
        tab.setClosable(false);
        return tab;
    }

    @Override
    public void stop() {
        if (context != null) {
            context.shutdown();
        }
    }
}
