package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.application.MealPlanningFacade;
import io.github.ayech14.mealiq.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * The desktop interface: a sidebar of pages, starting at Home, where the agent's check-up
 * suggests the next step. Every page calls {@link MealPlanningFacade} like the CLI does, so
 * both give the same results (D02). Start it with {@code ./mealiq-gui}.
 */
public final class MealPlanningGUI extends Application {

    /** Page keys with their sidebar labels, in sidebar order. */
    static final Map<String, String> PAGES = pages();

    private GuiContext context;

    private static Map<String, String> pages() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("home", "Home");
        pages.put("assistant", "Ask the agent");
        pages.put("plan", "Meal plan");
        pages.put("pantry", "Pantry");
        pages.put("recipes", "Recipes");
        pages.put("groceries", "Groceries");
        pages.put("profile", "Profile");
        return pages;
    }

    @Override
    public void start(Stage stage) {
        context = new GuiContext(MealPlanningFacade.fromConfig(AppConfig.load()));
        stage.setTitle("MealIQ");
        stage.setScene(new Scene(build(context), 1180, 800));
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.setOnCloseRequest(e -> context.shutdown());
        stage.show();
        context.dataChanged();
    }

    /** The whole window content for a context; also used to render the screens in tests. */
    static BorderPane build(GuiContext context) {
        Map<String, Node> views = new LinkedHashMap<>();
        views.put("home", new HomeView(context).view());
        AssistantView assistant = new AssistantView(context);
        context.agent(assistant::send);
        views.put("assistant", assistant.view());
        views.put("plan", new PlanView(context).view());
        views.put("pantry", new PantryView(context).view());
        views.put("recipes", new RecipesView(context).view());
        views.put("groceries", new GroceryView(context).view());
        views.put("profile", new ProfileView(context).view());

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app");
        root.getStylesheets().add(MealPlanningGUI.class.getResource("mealiq.css").toExternalForm());

        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.getChildren().addAll(Ui.styled(new Label("MealIQ"), "brand"),
                Ui.styled(new Label("AI meal-planning agent"), "brand-tagline"));
        ToggleGroup group = new ToggleGroup();
        Map<String, ToggleButton> buttons = new LinkedHashMap<>();
        PAGES.forEach((key, label) -> {
            ToggleButton button = new ToggleButton(label);
            button.getStyleClass().add("nav-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setToggleGroup(group);
            button.setOnAction(e -> context.navigate(key));
            buttons.put(key, button);
            sidebar.getChildren().add(button);
        });
        Region gap = new Region();
        VBox.setVgrow(gap, Priority.ALWAYS);
        TextField profileName = new TextField(context.profileName());
        profileName.setPrefColumnCount(8);
        Button switchProfile = new Button("Switch");
        switchProfile.setOnAction(e -> context.profileName(profileName.getText()));
        switchProfile.disableProperty().bind(context.busy());
        profileName.setOnAction(e -> context.profileName(profileName.getText()));
        HBox.setHgrow(profileName, Priority.ALWAYS);
        sidebar.getChildren().addAll(gap, Ui.styled(new Label("Profile"), "sidebar-label"),
                Ui.row(6, profileName, switchProfile));

        context.navigator(key -> {
            root.setCenter(views.get(key));
            buttons.get(key).setSelected(true);
        });
        context.navigate("home");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);
        spinner.visibleProperty().bind(context.busy());
        Label status = new Label();
        status.textProperty().bind(context.status());
        HBox footer = Ui.row(8, spinner, status, Ui.spacer(),
                new Label("A planning aid, not dietary advice."));
        footer.getStyleClass().add("status-bar");

        root.setLeft(sidebar);
        root.setBottom(footer);
        return root;
    }

    @Override
    public void stop() {
        if (context != null) {
            context.shutdown();
        }
    }
}
