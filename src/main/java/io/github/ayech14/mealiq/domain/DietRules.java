package io.github.ayech14.mealiq.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Hard dietary-restriction rules used by {@link UserProfile#isAllowed(Ingredient)}.
 *
 * <p>A restriction excludes ingredients by catalogue category and/or allergen tag. Stage 1
 * names restrictions as free-form text; this table makes the supported ones explicit so
 * they can be enforced deterministically (D04).
 */
public final class DietRules {

    public record Rule(Set<String> excludedCategories, Set<String> excludedAllergens) {
    }

    private static final Set<String> MEAT = Set.of("meat", "poultry");
    private static final Set<String> SEAFOOD = Set.of("fish", "shellfish");

    private static final Map<String, Rule> RULES = Map.of(
            "vegetarian", new Rule(union(MEAT, SEAFOOD), Set.of()),
            "pescatarian", new Rule(MEAT, Set.of()),
            "vegan", new Rule(union(MEAT, SEAFOOD, Set.of("dairy", "egg", "bee-product")),
                    Set.of("milk", "egg", "fish", "shellfish")),
            "gluten-free", new Rule(Set.of(), Set.of("gluten")),
            "dairy-free", new Rule(Set.of("dairy"), Set.of("milk")),
            "nut-free", new Rule(Set.of(), Set.of("tree-nut", "peanut")));

    private DietRules() {
    }

    /** Lower case, with spaces and underscores turned into hyphens ("Gluten Free" -> "gluten-free"). */
    public static String normalizeTag(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    public static boolean isSupported(String restriction) {
        return RULES.containsKey(normalizeTag(restriction));
    }

    public static Rule ruleFor(String restriction) {
        return RULES.get(normalizeTag(restriction));
    }

    public static Set<String> supportedRestrictions() {
        return new TreeSet<>(RULES.keySet());
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> all = new TreeSet<>();
        for (Set<String> set : sets) {
            all.addAll(set);
        }
        return Set.copyOf(all);
    }
}
