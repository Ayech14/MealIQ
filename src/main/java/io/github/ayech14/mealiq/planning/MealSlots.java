package io.github.ayech14.mealiq.planning;

import io.github.ayech14.mealiq.domain.MealType;
import java.util.ArrayList;
import java.util.List;

/**
 * The meal types a day of the plan holds, by meals per day: one meal is dinner, two add
 * lunch, three add breakfast, and every meal beyond three is a snack.
 */
public final class MealSlots {

    private MealSlots() {
    }

    public static List<MealType> forDay(int mealsPerDay) {
        if (mealsPerDay < 1) {
            throw new IllegalArgumentException("mealsPerDay must be at least 1");
        }
        List<MealType> slots = new ArrayList<>();
        if (mealsPerDay >= 3) {
            slots.add(MealType.BREAKFAST);
        }
        if (mealsPerDay >= 2) {
            slots.add(MealType.LUNCH);
        }
        slots.add(MealType.DINNER);
        for (int i = 3; i < mealsPerDay; i++) {
            slots.add(MealType.SNACK);
        }
        return List.copyOf(slots);
    }
}
