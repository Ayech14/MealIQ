package io.github.ayech14.mealiq.persistence;

import io.github.ayech14.mealiq.domain.Ingredient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngredientRepository {

    Optional<Ingredient> findById(UUID ingredientId);

    /** Case-insensitive exact match on the catalogue name. */
    Optional<Ingredient> findByName(String name);

    List<Ingredient> findSubstitutes(Ingredient ingredient);

    /** Inserts or updates a catalogue entry (used by the catalogue import). */
    void save(Ingredient ingredient);

    /** Replaces the substitute list of an ingredient; all must already be saved. */
    void saveSubstitutes(Ingredient ingredient, List<Ingredient> substitutes);
}
