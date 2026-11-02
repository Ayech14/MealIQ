package io.github.ayech14.mealiq.presentation.cli;

import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.domain.WeightGoal;
import io.github.ayech14.mealiq.services.NotFoundException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** {@code mealiq profile show|set}: goals, constraints and preferences (F01, UC01). */
@Command(name = "profile", description = "Show or change your goals and preferences.",
        mixinStandardHelpOptions = true)
final class ProfileCommand implements Runnable {

    @ParentCommand
    MealPlanningCLI cli;

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(cli.out());
    }

    @Command(name = "show", description = "Show the saved profile.")
    int show() {
        UserProfile profile;
        try {
            profile = cli.facade().getProfile(cli.profileId());
        } catch (NotFoundException e) {
            cli.out().println("No profile '" + cli.profileName + "' yet. Create one with: mealiq profile set");
            return MealPlanningCLI.FAILED;
        }
        print(profile);
        return MealPlanningCLI.OK;
    }

    @Command(name = "set", description = {
        "Create the profile or change some of its fields; fields not given keep their value.",
        "Lists are comma-separated; an empty value (\"\") clears a list."})
    int set(
            @Option(names = "--goal", paramLabel = "GOAL", description = "gain, lose or maintain") WeightGoal goal,
            @Option(names = "--calories", paramLabel = "KCAL", description = "Daily calorie target")
            Integer calories,
            @Option(names = "--protein", paramLabel = "GRAMS", description = "Daily protein target")
            Integer protein,
            @Option(names = "--diet", paramLabel = "RULES", split = ",",
                    description = "Dietary rules, e.g. vegetarian,gluten-free") List<String> diet,
            @Option(names = "--allergies", paramLabel = "ALLERGENS", split = ",",
                    description = "Allergens, e.g. peanut,milk") List<String> allergies,
            @Option(names = "--likes", paramLabel = "FOODS", split = ",", description = "Preferred foods")
            List<String> likes,
            @Option(names = "--dislikes", paramLabel = "FOODS", split = ",", description = "Disliked foods")
            List<String> dislikes,
            @Option(names = "--meals-per-day", paramLabel = "N") Integer mealsPerDay,
            @Option(names = "--budget", paramLabel = "AMOUNT", description = "Weekly grocery budget")
            BigDecimal budget,
            @Option(names = "--cook-minutes", paramLabel = "DAY=MIN", split = ",",
                    description = "Cooking-time limits, replacing the current ones: e.g. mon=20,sat=60 or all=30; "
                            + "\"\" removes them")
            List<String> cookMinutes) {
        UserProfile profile;
        try {
            profile = cli.facade().getProfile(cli.profileId());
        } catch (NotFoundException e) {
            profile = new UserProfile(cli.profileId());
        }
        if (goal != null) {
            profile.goal(goal);
        }
        if (calories != null) {
            profile.dailyCalorieTarget(calories);
        }
        if (protein != null) {
            profile.dailyProteinTargetG(protein);
        }
        if (diet != null) {
            profile.dietaryRestrictions(values(diet));
        }
        if (allergies != null) {
            profile.allergies(values(allergies));
        }
        if (likes != null) {
            profile.preferredFoods(values(likes));
        }
        if (dislikes != null) {
            profile.dislikedFoods(values(dislikes));
        }
        if (mealsPerDay != null) {
            profile.mealsPerDay(mealsPerDay);
        }
        if (budget != null) {
            profile.weeklyBudget(budget);
        }
        if (cookMinutes != null) {
            profile.maxCookMinutes(cookMinutes(cookMinutes));
        }
        UserProfile saved = cli.facade().updateProfile(profile);
        cli.out().println("Profile '" + cli.profileName + "' saved.");
        print(saved);
        cli.facade().profileWarnings(saved).forEach(w -> cli.out().println("Warning: " + w));
        return MealPlanningCLI.OK;
    }

    private void print(UserProfile p) {
        PrintWriter out = cli.out();
        out.println("Profile: " + cli.profileName);
        row(out, "Goal", p.goal().name().toLowerCase(Locale.ROOT));
        row(out, "Calorie target", p.dailyCalorieTarget() == null ? "not set" : p.dailyCalorieTarget() + " kcal/day");
        row(out, "Protein target", p.dailyProteinTargetG() == null ? "not set" : p.dailyProteinTargetG() + " g/day");
        row(out, "Diet", list(p.dietaryRestrictions()));
        row(out, "Allergies", list(p.allergies()));
        row(out, "Likes", list(p.preferredFoods()));
        row(out, "Dislikes", list(p.dislikedFoods()));
        row(out, "Meals per day", String.valueOf(p.mealsPerDay()));
        row(out, "Weekly budget", "$" + p.weeklyBudget().setScale(2, RoundingMode.HALF_UP));
        row(out, "Cooking time", p.maxCookMinutes().isEmpty() ? "no limit"
                : new TreeMap<>(p.maxCookMinutes()).entrySet().stream()
                        .map(e -> day(e.getKey()) + " " + e.getValue() + " min")
                        .collect(Collectors.joining(", ")));
    }

    private static void row(PrintWriter out, String label, String value) {
        out.printf("  %-15s %s%n", label + ":", value);
    }

    private static String list(Set<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values.stream().sorted().toList());
    }

    private static String day(DayOfWeek day) {
        String name = day.name();
        return name.charAt(0) + name.substring(1, 3).toLowerCase(Locale.ROOT);
    }

    /** Drops blanks, so {@code --allergies ""} clears the list. */
    private static Set<String> values(List<String> raw) {
        return raw.stream().map(String::strip).filter(v -> !v.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Parses {@code mon=20,sat=60}; {@code all=30} sets every day. Ranges are checked by the service. */
    private Map<DayOfWeek, Integer> cookMinutes(List<String> entries) {
        Map<DayOfWeek, Integer> limits = new EnumMap<>(DayOfWeek.class);
        for (String entry : values(entries)) {
            String[] parts = entry.split("=", 2);
            Integer minutes = parts.length == 2 ? parseMinutes(parts[1].strip()) : null;
            List<DayOfWeek> days = parts.length == 2 ? parseDays(parts[0].strip()) : List.of();
            if (minutes == null || days.isEmpty()) {
                throw new ParameterException(spec.commandLine(),
                        "--cook-minutes: expected DAY=MINUTES (e.g. mon=20 or all=30) but got '" + entry + "'");
            }
            days.forEach(d -> limits.put(d, minutes));
        }
        return limits;
    }

    private static List<DayOfWeek> parseDays(String text) {
        String key = text.toUpperCase(Locale.ROOT);
        if (key.equals("ALL")) {
            return List.of(DayOfWeek.values());
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            if (key.length() >= 3 && day.name().startsWith(key)) {
                return List.of(day);
            }
        }
        return List.of();
    }

    private static Integer parseMinutes(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
