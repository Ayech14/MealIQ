package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.domain.Recipe;
import java.util.List;

/**
 * A generated recipe and the problems its last attempt still had; usable only when
 * {@code problems} is empty. {@code attempts} counts the LLM calls made (at most two).
 */
public record RecipeDraft(Recipe recipe, List<String> problems, int attempts) {

    public RecipeDraft {
        problems = List.copyOf(problems);
    }

    public boolean usable() {
        return recipe != null && problems.isEmpty();
    }
}
