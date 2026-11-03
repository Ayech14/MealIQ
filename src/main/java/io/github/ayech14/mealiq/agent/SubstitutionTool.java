package io.github.ayech14.mealiq.agent;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code substituteIngredient}: catalogue alternatives for an ingredient that the user's
 * allergies and diet allow and that are not on the dislike list (F09).
 */
public class SubstitutionTool implements AgentTool {

    public static final String NAME = "substituteIngredient";
    private static final Set<String> ARGS = Set.of("ingredient");

    private final IngredientRepository ingredientRepository;

    public SubstitutionTool(IngredientRepository ingredientRepository) {
        this.ingredientRepository = ingredientRepository;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String description() {
        return "compatible replacements for an ingredient. Arguments: ingredient (catalogue name, required)";
    }

    @Override
    public List<String> checkArguments(Map<String, Object> args) {
        ToolArguments read = new ToolArguments(args, ARGS);
        String name = read.text("ingredient", true);
        if (name != null && ingredientRepository.findByName(name).isEmpty()) {
            read.problems().add("'" + name + "' is not in the ingredient catalogue");
        }
        return read.problems();
    }

    public List<Ingredient> findCandidates(Ingredient target, PlanningContext context) {
        return ingredientRepository.findSubstitutes(target).stream()
                .filter(i -> context.profile().isAllowed(i))
                .filter(i -> !context.profile().dislikedFoods().contains(i.name()))
                .toList();
    }

    @Override
    public ToolResult execute(Map<String, Object> args, PlanningContext context) {
        Ingredient target = ingredientRepository.findByName(new ToolArguments(args, ARGS).text("ingredient", true))
                .orElseThrow();
        List<Ingredient> candidates = findCandidates(target, context);
        String summary = candidates.isEmpty()
                ? "No compatible substitute for " + target.name() + "."
                : "Substitutes for " + target.name() + ", best first: "
                        + String.join(", ", candidates.stream().map(Ingredient::name).toList());
        return ToolResult.ok(NAME, candidates, summary);
    }
}
