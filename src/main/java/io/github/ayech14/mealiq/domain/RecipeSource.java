package io.github.ayech14.mealiq.domain;

/**
 * Where a recipe comes from. A VARIANT is a stored recipe with one ingredient substituted
 * (F09); variants exist only for the plans that use them and are never search results.
 */
public enum RecipeSource {
    CURATED, EXTERNAL, GENERATED, VARIANT
}
