package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.contracts.PlanningProposal;
import io.github.ayech14.mealiq.contracts.ValidationResult;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import io.github.ayech14.mealiq.planning.MealSlots;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Structural checks of an agent proposal (SD01): every meal is a stored recipe of the right
 * meal type, on a day inside the plan, and every slot is filled exactly once.
 */
public class ProposalValidator {

    /** A recipe used more often than this in one plan gets a variety warning. */
    static final int MAX_REPEATS = 2;

    private final RecipeRepository recipeRepository;

    public ProposalValidator(RecipeRepository recipeRepository) {
        this.recipeRepository = recipeRepository;
    }

    public ValidationResult validate(PlanningProposal proposal, PlanningContext context) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        LocalDate start = context.request().startDate();
        int days = context.request().days();
        List<MealType> slots = MealSlots.forDay(context.profile().mealsPerDay());

        Map<String, Integer> filled = new HashMap<>();
        Map<UUID, Integer> uses = new HashMap<>();
        for (PlannedMeal meal : proposal.meals()) {
            String slot = label(meal.date(), meal.mealType(), start);
            if (meal.date().isBefore(start) || !meal.date().isBefore(start.plusDays(days))) {
                violations.add(slot + " is outside the plan's dates.");
                continue;
            }
            if (recipeRepository.findById(meal.recipe().id()).isEmpty()) {
                violations.add(slot + ": " + meal.recipe().name() + " is not a stored recipe.");
            } else if (!meal.recipe().mealTypes().contains(meal.mealType())) {
                violations.add(slot + ": " + meal.recipe().name() + " is not a "
                        + meal.mealType().name().toLowerCase(Locale.ROOT) + " recipe.");
            }
            filled.merge(meal.date() + "/" + meal.mealType(), 1, Integer::sum);
            uses.merge(meal.recipe().id(), 1, Integer::sum);
        }
        for (int day = 0; day < days; day++) {
            LocalDate date = start.plusDays(day);
            for (MealType type : slots.stream().distinct().toList()) {
                int wanted = (int) slots.stream().filter(type::equals).count();
                int got = filled.getOrDefault(date + "/" + type, 0);
                if (got != wanted) {
                    violations.add(label(date, type, start) + ": expected " + wanted + " meal(s), got " + got + ".");
                }
            }
        }
        filled.keySet().stream()
                .filter(key -> slots.stream().noneMatch(t -> key.endsWith("/" + t)))
                .forEach(key -> violations.add("A " + key.substring(key.indexOf('/') + 1).toLowerCase(Locale.ROOT)
                        + " meal is not part of a " + slots.size() + "-meal day."));
        Map<String, Integer> sameDay = new HashMap<>();
        proposal.meals().forEach(m -> sameDay.merge(m.date() + "/" + m.recipe().name(), 1, Integer::sum));
        sameDay.forEach((key, count) -> {
            if (count > 1) {
                LocalDate date = LocalDate.parse(key.substring(0, key.indexOf('/')));
                warnings.add(key.substring(key.indexOf('/') + 1) + " is planned " + count + " times on day "
                        + (ChronoUnit.DAYS.between(start, date) + 1) + ".");
            }
        });
        uses.forEach((recipeId, count) -> {
            if (count > MAX_REPEATS) {
                String name = proposal.meals().stream().filter(m -> m.recipe().id().equals(recipeId))
                        .findFirst().orElseThrow().recipe().name();
                warnings.add(name + " appears " + count + " times; consider more variety.");
            }
        });
        return new ValidationResult(violations.isEmpty(), violations, warnings);
    }

    /** "Day 2 (2026-11-04) lunch", so messages read the way the plan is shown. */
    static String label(LocalDate date, MealType type, LocalDate start) {
        return "Day " + (ChronoUnit.DAYS.between(start, date) + 1) + " (" + date + ") "
                + type.name().toLowerCase(Locale.ROOT);
    }
}
