package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.application.MealPlanningFacade;
import io.github.ayech14.mealiq.application.TestFacades;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.domain.WeightGoal;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javax.imageio.ImageIO;

/**
 * Renders every GUI screen to a PNG with in-memory data and no window, to inspect the layout:
 * {@code java -cp ... io.github.ayech14.mealiq.presentation.gui.GuiSnapshots OUTPUT_FOLDER}.
 * Not a unit test, because rendering needs a display.
 */
public final class GuiSnapshots {

    private GuiSnapshots() {
    }

    public static void main(String[] args) throws Exception {
        File folder = new File(args.length > 0 ? args[0] : "target/gui-snapshots");
        folder.mkdirs();
        LocalDate today = LocalDate.of(2026, 11, 3);
        TestFacades.Fixture fixture = TestFacades.inMemory(today);
        MealPlanningFacade facade = fixture.facade();
        UUID profileId = MealPlanningFacade.localProfileId("default");
        facade.updateProfile(new UserProfile(profileId).goal(WeightGoal.MAINTAIN).dailyCalorieTarget(2000)
                .dailyProteinTargetG(110).allergies(Set.of("peanut")).weeklyBudget(new BigDecimal("60"))
                .maxCookMinutes(Map.of(DayOfWeek.WEDNESDAY, 20)));
        facade.updatePantry(profileId, facade.pantryItem("egg", 12, "unit", null));
        facade.updatePantry(profileId, facade.pantryItem("rolled oats", 1, "kg", null));
        facade.updatePantry(profileId, facade.pantryItem("milk", 1, "l", today.minusDays(1)));
        MealPlan plan = new MealPlan(UUID.randomUUID(), profileId, today, 2, PlanStatus.ACTIVE, List.of(
                meal(facade, today, MealType.BREAKFAST, "Overnight oats"),
                meal(facade, today, MealType.LUNCH, "Turkey veggie wrap"),
                meal(facade, today, MealType.DINNER, "Chicken stir-fry"),
                meal(facade, today.plusDays(1), MealType.BREAKFAST, "Spinach scrambled eggs on toast"),
                meal(facade, today.plusDays(1), MealType.LUNCH, "Lentil soup"),
                meal(facade, today.plusDays(1), MealType.DINNER, "Shrimp tacos")));
        fixture.plans().save(plan);
        facade.generateOptimizedGroceryList(profileId);
        // Wednesday's limit becomes tighter, so the plan shows an impact notice.
        facade.updateProfile(facade.getProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.WEDNESDAY, 20)));
        facade.updateProfile(facade.getProfile(profileId).maxCookMinutes(Map.of(DayOfWeek.WEDNESDAY, 15)));

        Platform.startup(() -> { });
        GuiContext context = new GuiContext(facade);
        BorderPane[] root = new BorderPane[1];
        onFx(() -> {
            root[0] = MealPlanningGUI.build(context);
            new Scene(root[0], 1100, 760);
            context.dataChanged();
        });
        settle(context);
        TabPane tabs = (TabPane) root[0].getCenter();
        // Only the selected tab is in the scene, so open Recipes before pressing its Browse button.
        onFx(() -> {
            tabs.getSelectionModel().select(2);
            root[0].applyCss(); // without a window, the tab's controls exist only after a layout pass
            root[0].layout();
        });
        onFx(() -> root[0].lookupAll(".button").stream().filter(n -> n instanceof Button b
                && b.getText().equals("Browse")).findFirst().map(Button.class::cast).ifPresent(Button::fire));
        settle(context);
        onFx(() -> tabs.lookupAll(".table-view").stream().map(t -> (TableView<?>) t)
                .filter(t -> t.getColumns().getFirst().getText().equals("Recipe")).findFirst()
                .ifPresent(t -> t.getSelectionModel().select(1)));
        settle(context);
        String[] names = {"profile", "pantry", "recipes", "plan", "groceries"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            onFx(() -> tabs.getSelectionModel().select(index));
            File file = new File(folder, (i + 1) + "-" + names[i] + ".png");
            onFx(() -> save(root[0].snapshot(null, null), file));
            System.out.println("Wrote " + file);
        }
        context.shutdown();
    }

    private static PlannedMeal meal(MealPlanningFacade facade, LocalDate date, MealType type, String recipe) {
        return new PlannedMeal(date, type, facade.findRecipe(recipe), 1);
    }

    /** Waits until every queued background task and its screen update have run. */
    private static void settle(GuiContext context) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> context.run("", () -> null, v -> done.countDown()));
        if (!done.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("The screens did not finish loading");
        }
    }

    private static void onFx(Runnable work) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } finally {
                done.countDown();
            }
        });
        done.await(20, TimeUnit.SECONDS);
    }

    private static void save(WritableImage image, File file) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(buffered, "png", file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
