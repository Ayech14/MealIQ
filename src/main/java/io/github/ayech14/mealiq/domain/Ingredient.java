package io.github.ayech14.mealiq.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A catalogue ingredient. Nutrition is stored per 100 g (D09). The gram weights convert
 * counts ("2 eggs") and volumes ("1 cup rice") to grams; null means the catalogue has no
 * weight for that kind of unit, so such quantities give incomplete nutrition.
 *
 * @param fdcId source record in USDA FoodData Central, kept for provenance (nullable)
 */
public record Ingredient(
        UUID id,
        String name,
        String category,
        Set<String> allergens,
        NutritionSummary nutritionPer100g,
        Double gramsPerUnit,
        Double gramsPerMl,
        Integer fdcId) {

    public Ingredient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        category = DietRules.normalizeTag(Objects.requireNonNull(category, "category"));
        allergens = allergens == null ? Set.of()
                : allergens.stream().map(DietRules::normalizeTag).collect(Collectors.toUnmodifiableSet());
    }

    /** A catalogue entry without nutrition or unit weights, for tests and minimal data. */
    public static Ingredient of(String name, String category, String... allergens) {
        return new Ingredient(UUID.randomUUID(), name, category, Set.of(allergens), null, null, null, null);
    }

    public Ingredient withNutrition(NutritionSummary per100g) {
        return new Ingredient(id, name, category, allergens, per100g, gramsPerUnit, gramsPerMl, fdcId);
    }

    public Ingredient withUnitWeights(Double gramsPerUnit, Double gramsPerMl) {
        return new Ingredient(id, name, category, allergens, nutritionPer100g, gramsPerUnit, gramsPerMl, fdcId);
    }

    public Optional<NutritionSummary> nutrition() {
        return Optional.ofNullable(nutritionPer100g);
    }

    /** Weight of a quantity of this ingredient, or empty when it cannot be converted. */
    public OptionalDouble toGrams(Quantity quantity) {
        if (quantity.isZero()) {
            return OptionalDouble.of(0);
        }
        return switch (quantity.dimension()) {
            case MASS -> OptionalDouble.of(quantity.convertTo(Unit.G).amount());
            case VOLUME -> gramsPerMl == null ? OptionalDouble.empty()
                    : OptionalDouble.of(quantity.convertTo(Unit.ML).amount() * gramsPerMl);
            case COUNT -> gramsPerUnit == null ? OptionalDouble.empty()
                    : OptionalDouble.of(quantity.convertTo(Unit.UNIT).amount() * gramsPerUnit);
        };
    }
}
