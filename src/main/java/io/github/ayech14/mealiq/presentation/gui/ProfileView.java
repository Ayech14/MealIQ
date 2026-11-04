package io.github.ayech14.mealiq.presentation.gui;

import io.github.ayech14.mealiq.contracts.PlanImpact;
import io.github.ayech14.mealiq.domain.DietRules;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.domain.WeightGoal;
import io.github.ayech14.mealiq.services.NotFoundException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/** Goals, limits and preferences (F01, UC01), with the impact on the plan after saving (F13). */
final class ProfileView {

    private final GuiContext context;
    private final ComboBox<WeightGoal> goal = new ComboBox<>();
    private final TextField calories = new TextField();
    private final TextField protein = new TextField();
    private final Map<String, CheckBox> diet = new LinkedHashMap<>();
    private final Map<String, CheckBox> allergies = new LinkedHashMap<>();
    private final TextField likes = new TextField();
    private final TextField dislikes = new TextField();
    private final Spinner<Integer> mealsPerDay = new Spinner<>(1, 6, 3);
    private final TextField budget = new TextField();
    private final Map<DayOfWeek, TextField> minutes = new EnumMap<>(DayOfWeek.class);
    private final Label message = new Label();
    private final VBox root = new VBox(12);

    ProfileView(GuiContext context) {
        this.context = context;
        goal.getItems().setAll(WeightGoal.values());
        goal.setConverter(new StringConverter<>() {
            @Override
            public String toString(WeightGoal value) {
                return value == null ? "" : value.name().toLowerCase(Locale.ROOT);
            }

            @Override
            public WeightGoal fromString(String text) {
                return WeightGoal.valueOf(text.toUpperCase(Locale.ROOT));
            }
        });
        DietRules.supportedRestrictions().forEach(rule -> diet.put(rule, new CheckBox(rule)));
        DietRules.knownAllergens().forEach(allergen -> allergies.put(allergen, new CheckBox(allergen)));
        likes.setPromptText("comma-separated foods");
        dislikes.setPromptText("comma-separated foods");
        budget.setPromptText("weekly, e.g. 80");
        mealsPerDay.setEditable(true);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        String[] labels = {"Goal", "Calories per day", "Protein per day (g)", "Diet", "Allergies", "Likes",
            "Dislikes", "Meals per day", "Weekly budget ($)"};
        Node[] fields = {goal, calories, protein, checkboxes(diet), checkboxes(allergies), likes, dislikes,
            mealsPerDay, budget};
        for (int i = 0; i < labels.length; i++) {
            form.addRow(i, new Label(labels[i]), fields[i]);
        }
        HBox days = new HBox(6);
        for (DayOfWeek day : DayOfWeek.values()) {
            TextField field = new TextField();
            field.setPrefColumnCount(3);
            field.setPromptText("-");
            minutes.put(day, field);
            days.getChildren().add(new VBox(2, new Label(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)), field));
        }
        form.addRow(labels.length, new Label("Max cooking minutes"), days);

        Button save = Ui.primary(new Button("Save profile"));
        save.setDefaultButton(true);
        save.disableProperty().bind(context.busy());
        save.setOnAction(e -> save());
        message.setWrapText(true);
        root.getStyleClass().add("page");
        root.getChildren().addAll(Ui.styled(new Label("Profile"), "page-title"),
                Ui.wrapped("Allergies and diet are hard limits: MealIQ never suggests food that breaks them. Likes "
                        + "and dislikes are preferences. A planning aid, not dietary advice.", "page-subtitle"),
                Ui.card(null, form), Ui.row(8, save), message);
        context.onDataChanged(this::load);
    }

    Node view() {
        return Ui.scrolling(root);
    }

    void load() {
        context.run("Loading profile...", () -> {
            try {
                return Optional.of(context.facade().getProfile(context.profileId()));
            } catch (NotFoundException e) {
                return Optional.<UserProfile>empty();
            }
        }, this::show);
    }

    private void show(Optional<UserProfile> saved) {
        UserProfile p = saved.orElseGet(() -> new UserProfile(context.profileId()));
        goal.setValue(p.goal());
        calories.setText(p.dailyCalorieTarget() == null ? "" : p.dailyCalorieTarget().toString());
        protein.setText(p.dailyProteinTargetG() == null ? "" : p.dailyProteinTargetG().toString());
        diet.forEach((rule, box) -> box.setSelected(p.dietaryRestrictions().contains(rule)));
        allergies.forEach((allergen, box) -> box.setSelected(p.allergies().contains(allergen)));
        likes.setText(join(p.preferredFoods()));
        dislikes.setText(join(p.dislikedFoods()));
        mealsPerDay.getValueFactory().setValue(p.mealsPerDay());
        budget.setText(p.weeklyBudget().signum() == 0 ? "" : p.weeklyBudget().toPlainString());
        minutes.forEach((day, field) -> field.setText(p.maxMinutesFor(day).isPresent()
                ? String.valueOf(p.maxMinutesFor(day).getAsInt()) : ""));
        message.setText(saved.isEmpty() ? "No profile '" + context.profileName() + "' yet: fill it in and save." : "");
    }

    private void save() {
        UserProfile profile;
        try {
            profile = new UserProfile(context.profileId()).goal(goal.getValue())
                    .dailyCalorieTarget(number(calories, "Calories"))
                    .dailyProteinTargetG(number(protein, "Protein"))
                    .dietaryRestrictions(selected(diet)).allergies(selected(allergies))
                    .preferredFoods(list(likes)).dislikedFoods(list(dislikes))
                    .mealsPerDay(mealsPerDay.getValue())
                    .weeklyBudget(budget.getText().isBlank() ? BigDecimal.ZERO
                            : new BigDecimal(budget.getText().strip()))
                    .maxCookMinutes(cookMinutes());
        } catch (NumberFormatException e) {
            context.showError(new IllegalArgumentException(e.getMessage()));
            return;
        }
        context.run("Saving profile...", () -> {
            UserProfile saved = context.facade().updateProfile(profile);
            List<String> notes = context.facade().profileWarnings(saved);
            Optional<PlanImpact> impact = context.facade().pendingImpact(context.profileId());
            return new Saved(notes, impact);
        }, saved -> {
            StringBuilder text = new StringBuilder("Profile saved.");
            saved.warnings().forEach(w -> text.append("\nWarning: ").append(w));
            saved.impact().ifPresent(i -> text.append("\nThis affects your meal plan:\n  ")
                    .append(String.join("\n  ", i.conflicts())).append("\nOpen the Plan tab and choose Adapt."));
            message.setText(text.toString());
            context.dataChanged();
        });
    }

    private record Saved(List<String> warnings, Optional<PlanImpact> impact) {
    }

    private Map<DayOfWeek, Integer> cookMinutes() {
        Map<DayOfWeek, Integer> limits = new EnumMap<>(DayOfWeek.class);
        minutes.forEach((day, field) -> {
            if (!field.getText().isBlank()) {
                limits.put(day, Integer.valueOf(field.getText().strip()));
            }
        });
        return limits;
    }

    private static Integer number(TextField field, String name) {
        if (field.getText().isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(field.getText().strip());
        } catch (NumberFormatException e) {
            throw new NumberFormatException(name + " must be a whole number");
        }
    }

    private static FlowPane checkboxes(Map<String, CheckBox> boxes) {
        FlowPane pane = new FlowPane(12, 6);
        pane.getChildren().addAll(boxes.values());
        pane.setPrefWrapLength(560);
        return pane;
    }

    private static Set<String> selected(Map<String, CheckBox> boxes) {
        return boxes.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> list(TextField field) {
        return Arrays.stream(field.getText().split(",")).map(String::strip).filter(v -> !v.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String join(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(", "));
    }
}
