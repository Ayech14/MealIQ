package io.github.ayech14.mealiq.application;

import io.github.ayech14.mealiq.agent.ConversationMemory;
import io.github.ayech14.mealiq.agent.MealPlanningAgent;
import io.github.ayech14.mealiq.agent.NutritionTool;
import io.github.ayech14.mealiq.agent.PromptBuilder;
import io.github.ayech14.mealiq.agent.RecipeTool;
import io.github.ayech14.mealiq.agent.SubstitutionTool;
import io.github.ayech14.mealiq.agent.ToolManager;
import io.github.ayech14.mealiq.llm.LLMClient;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import io.github.ayech14.mealiq.planning.PlanningStrategyFactory;
import io.github.ayech14.mealiq.services.ConstraintValidator;
import io.github.ayech14.mealiq.services.NutritionService;

/** Builds the agent with its tools; shared by the application wiring and tests. */
public final class AgentAssembly {

    static final int MEMORY_ENTRIES = 20;

    private AgentAssembly() {
    }

    public static MealPlanningAgent agent(LLMClient llm, RecipeRepository recipes, IngredientRepository ingredients,
                                          NutritionService nutrition, ConstraintValidator validator,
                                          PlanningStrategyFactory strategies, int maxToolSteps) {
        ToolManager tools = new ToolManager();
        tools.register(new RecipeTool(recipes, validator, nutrition));
        tools.register(new NutritionTool(nutrition, recipes));
        tools.register(new SubstitutionTool(ingredients));
        return new MealPlanningAgent(llm, tools, new PromptBuilder(nutrition), new ConversationMemory(MEMORY_ENTRIES),
                strategies, maxToolSteps);
    }
}
