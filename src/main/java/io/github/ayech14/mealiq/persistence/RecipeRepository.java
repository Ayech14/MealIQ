package io.github.ayech14.mealiq.persistence;

import io.github.ayech14.mealiq.contracts.RecipeQuery;
import io.github.ayech14.mealiq.domain.Recipe;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecipeRepository {

    Optional<Recipe> findById(UUID recipeId);

    /**
     * Recipes matching meal type, total time, source and ingredient filters. Cost filtering
     * needs price data and is applied by the caller, not here.
     */
    List<Recipe> search(RecipeQuery query);

    /** Saves a recipe; every ingredient must already be in the catalogue (D09). */
    void save(Recipe recipe);
}
