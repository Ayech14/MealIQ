package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.application.MealPlanningFacade;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import io.github.ayech14.mealiq.services.InvalidInputException;
import io.github.ayech14.mealiq.services.NotFoundException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;

/**
 * What every screen shares: the facade, the current profile, and a way to call the facade
 * off the JavaFX thread, so AI requests never freeze the window. Screens only collect
 * input and show results; every rule stays behind the facade (D02).
 */
final class GuiContext {

    private final MealPlanningFacade facade;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mealiq-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final StringProperty status = new SimpleStringProperty("Ready.");
    private final List<Runnable> dataListeners = new ArrayList<>();
    private String profileName = "default";
    private Consumer<String> navigator = page -> { };
    private Consumer<String> agent = message -> { };

    GuiContext(MealPlanningFacade facade) {
        this.facade = facade;
    }

    MealPlanningFacade facade() {
        return facade;
    }

    UUID profileId() {
        return MealPlanningFacade.localProfileId(profileName);
    }

    String profileName() {
        return profileName;
    }

    void profileName(String name) {
        profileName = name == null || name.isBlank() ? "default" : name.strip();
        dataChanged();
    }

    /** The shell registers how to open a page by name ("home", "plan", ...). */
    void navigator(Consumer<String> open) {
        navigator = open;
    }

    void navigate(String page) {
        navigator.accept(page);
    }

    /** The assistant registers how to send it a message. */
    void agent(Consumer<String> send) {
        agent = send;
    }

    /** Opens the assistant and sends it {@code message}, as if the user had typed it. */
    void askAgent(String message) {
        navigate("assistant");
        agent.accept(message);
    }

    BooleanProperty busy() {
        return busy;
    }

    StringProperty status() {
        return status;
    }

    /** Screens register here to reload when another screen changes shared data. */
    void onDataChanged(Runnable listener) {
        dataListeners.add(listener);
    }

    void dataChanged() {
        dataListeners.forEach(Runnable::run);
    }

    /**
     * Runs {@code work} on the worker thread and hands its result to {@code done} on the
     * JavaFX thread. Expected failures become a readable dialog; nothing is half applied.
     */
    <T> void run(String message, Supplier<T> work, Consumer<T> done) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() {
                return work.get();
            }
        };
        busy.set(true);
        status.set(message);
        task.setOnSucceeded(e -> {
            busy.set(false);
            status.set("Ready.");
            done.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            busy.set(false);
            status.set("Ready.");
            showError(task.getException());
        });
        worker.submit(task);
    }

    void showError(Throwable error) {
        String text = switch (error) {
            case InvalidInputException invalid -> "Not saved:\n" + String.join("\n", invalid.errors().entrySet()
                    .stream().map(e -> "  " + FIELD_NAMES.getOrDefault(e.getKey(), e.getKey()) + ": " + e.getValue())
                    .toList());
            case NotFoundException missing -> missing.getMessage();
            case PersistenceException database when cannotConnect(database) -> "MealIQ cannot reach its database."
                    + "\n\nStart it with: docker compose up -d\nThe first time, also run: ./mealiq setup";
            case PersistenceException database -> "Saving failed: " + database.getMessage();
            default -> "Something went wrong: " + error.getMessage();
        };
        Alert alert = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    /** Service field names as the screens label them. */
    private static final Map<String, String> FIELD_NAMES = Map.of(
            "dailyCalorieTarget", "Calories per day", "dailyProteinTargetG", "Protein per day",
            "dietaryRestrictions", "Diet", "allergies", "Allergies", "preferredFoods", "Likes",
            "dislikedFoods", "Dislikes", "mealsPerDay", "Meals per day", "weeklyBudget", "Weekly budget",
            "maxCookMinutes", "Max cooking minutes", "ingredient", "Ingredient");

    /** SQL state class 08 means the connection itself failed, not the statement. */
    private static boolean cannotConnect(PersistenceException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) {
                return true;
            }
        }
        return false;
    }

    void showInfo(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.getDialogPane().setContent(readOnly(text));
        alert.showAndWait();
    }

    /** A yes/no question with a long text preview; only an explicit "OK" counts as yes. */
    boolean confirm(String title, String preview) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.getDialogPane().setContent(readOnly(preview));
        alert.getDialogPane().setPrefWidth(640);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    static TextArea readOnly(String text) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(Math.min(18, Math.max(4, text.split("\n").length + 1)));
        return area;
    }

    void shutdown() {
        worker.shutdownNow();
        Platform.exit();
    }
}
