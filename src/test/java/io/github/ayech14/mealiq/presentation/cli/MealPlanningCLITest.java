package io.github.ayech14.mealiq.presentation.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.application.MealPlanningFacade;
import io.github.ayech14.mealiq.application.TestFacades;
import io.github.ayech14.mealiq.llm.LLMException;
import io.github.ayech14.mealiq.llm.ScriptedLLMClient;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import io.github.ayech14.mealiq.persistence.jdbc.DatabaseSetup;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs CLI commands end to end against in-memory repositories and checks what is printed and saved. */
class MealPlanningCLITest {

    private MealPlanningFacade facade;
    private ScriptedLLMClient llm;
    private TestFacades.Fixture fixture;
    private StringWriter out;
    private StringWriter err;
    private DatabaseSetup.Result setupResult = new DatabaseSetup.Result(List.of("profiles"), 146, 30);

    @BeforeEach
    void setUp() {
        fixture = TestFacades.inMemory(LocalDate.of(2026, 11, 2));
        facade = fixture.facade();
        llm = fixture.llm();
    }

    /** What the user types at confirmation questions in the next run. */
    private String input = "";

    private int run(String... args) {
        out = new StringWriter();
        err = new StringWriter();
        MealPlanningCLI cli = new MealPlanningCLI(facade, () -> {
            if (setupResult == null) {
                throw new PersistenceException("Database read failed: Connection refused");
            }
            return setupResult;
        }, new BufferedReader(new StringReader(input)), new PrintWriter(out, true), new PrintWriter(err, true));
        return cli.executeCommand(List.of(args));
    }

    @Test
    void noCommandPrintsUsage() {
        assertEquals(0, run());
        assertTrue(out.toString().contains("profile"));
        assertTrue(out.toString().contains("pantry"));
    }

    @Test
    void profileSetCreatesThenChangesOnlyGivenFields() {
        assertEquals(1, run("profile", "show"));
        assertTrue(out.toString().contains("No profile 'default' yet"));

        assertEquals(0, run("profile", "set", "--goal", "LOSE", "--calories", "1800", "--diet", "vegetarian",
                "--allergies", "peanut,Tree Nut", "--budget", "75.5", "--cook-minutes", "all=30,sat=60"));
        assertTrue(out.toString().contains("Profile 'default' saved."));

        assertEquals(0, run("profile", "set", "--meals-per-day", "4"));
        var profile = facade.getProfile(MealPlanningFacade.localProfileId("default"));
        assertEquals(4, profile.mealsPerDay());
        assertEquals(1800, profile.dailyCalorieTarget());
        assertEquals(Set.of("peanut", "tree-nut"), profile.allergies());
        assertEquals(30, profile.maxCookMinutes().get(DayOfWeek.MONDAY));
        assertEquals(60, profile.maxCookMinutes().get(DayOfWeek.SATURDAY));

        assertEquals(0, run("profile", "show"));
        String shown = out.toString();
        assertTrue(shown.contains("Goal:           lose"), shown);
        assertTrue(shown.contains("Allergies:      peanut, tree-nut"), shown);
        assertTrue(shown.contains("Weekly budget:  $75.50"), shown);
        assertTrue(shown.contains("Mon 30 min"), shown);
    }

    @Test
    void emptyValueClearsAList() {
        run("profile", "set", "--allergies", "milk", "--cook-minutes", "mon=20");
        assertEquals(0, run("profile", "set", "--allergies", "", "--cook-minutes", ""));
        var profile = facade.getProfile(MealPlanningFacade.localProfileId("default"));
        assertTrue(profile.allergies().isEmpty());
        assertEquals(Map.of(), profile.maxCookMinutes());
    }

    @Test
    void invalidFieldsAreReportedByOptionNameAndNothingIsSaved() {
        assertEquals(1, run("profile", "set", "--meals-per-day", "9", "--allergies", "peanuts"));
        String errors = err.toString();
        assertTrue(errors.contains("--meals-per-day: must be between 1 and 6"), errors);
        assertTrue(errors.contains("--allergies: unknown: peanuts"), errors);
        assertEquals(1, run("profile", "show"));
    }

    @Test
    void badCookMinutesIsAUsageError() {
        assertEquals(2, run("profile", "set", "--cook-minutes", "someday=20"));
        assertTrue(err.toString().contains("expected DAY=MINUTES"), err.toString());
    }

    @Test
    void conflictingPreferenceIsSavedWithAWarning() {
        assertEquals(0, run("profile", "set", "--diet", "vegetarian", "--likes", "salmon"));
        assertTrue(out.toString().contains("Warning: Preferred food 'salmon'"), out.toString());
    }

    @Test
    void profilesAreSeparateByName() {
        run("profile", "set", "--budget", "50");
        assertEquals(1, run("--profile", "sam", "profile", "show"));
        assertEquals(1, run("profile", "show", "--profile", "sam"));
        assertEquals(0, run("profile", "set", "--profile", "sam", "--budget", "20"));
        assertTrue(out.toString().contains("Profile 'sam' saved."));
    }

    @Test
    void pantrySetListAndRemove() {
        assertEquals(0, run("pantry", "list"));
        assertTrue(out.toString().contains("The pantry is empty"));

        assertEquals(0, run("pantry", "set", "soy milk", "1", "l", "--expires", "2000-01-01"));
        assertEquals("Pantry updated: soy milk 1 l.", out.toString().strip());
        assertEquals(0, run("pantry", "set", "Egg", "6", "unit"));

        assertEquals(0, run("pantry", "list"));
        String listed = out.toString();
        assertTrue(listed.contains("Pantry (default): 2 items"), listed);
        assertTrue(listed.indexOf("egg") < listed.indexOf("soy milk"), listed);
        assertTrue(listed.contains("expires 2000-01-01 (expired)"), listed);

        assertEquals(0, run("pantry", "remove", "egg"));
        assertEquals(1, run("pantry", "remove", "egg"));
        assertTrue(err.toString().contains("'egg' is not in the pantry"));
    }

    @Test
    void badPantryInputReportsEveryField() {
        assertEquals(1, run("pantry", "set", "unobtainium", "-2", "bushel"));
        String errors = err.toString();
        assertTrue(errors.contains("ingredient:") && errors.contains("quantity:") && errors.contains("unit:"),
                errors);
        assertTrue(errors.contains("mealiq ingredients TEXT"), errors);
        assertEquals(0, run("pantry", "list"));
        assertTrue(out.toString().contains("empty"));
    }

    @Test
    void ingredientsSearch() {
        assertEquals(0, run("ingredients", "milk"));
        assertTrue(out.toString().contains("soy milk"));
        assertEquals(1, run("ingredients", "unobtainium"));
    }

    @Test
    void setupReportsTablesAndCatalogue() {
        assertEquals(0, run("setup"));
        assertTrue(out.toString().contains("Created tables: profiles"));
        assertTrue(out.toString().contains("146 ingredients"));
    }

    @Test
    void databaseFailureGivesAHintInsteadOfAStackTrace() {
        setupResult = null;
        assertEquals(1, run("setup"));
        String errors = err.toString();
        assertTrue(errors.contains("Connection refused"), errors);
        assertTrue(errors.contains("docker compose up -d"), errors);
        assertFalse(errors.contains("at io.github"), errors);
    }

    @Test
    void setupReportsRecipes() {
        assertEquals(0, run("setup"));
        assertTrue(out.toString().contains("Seed recipes: 30 recipes."));
    }

    @Test
    void recipesListFiltersRanksAndExplainsExclusions() {
        run("profile", "set", "--allergies", "fish", "--cook-minutes", "all=20");
        assertEquals(0, run("recipes", "list", "--meal", "dinner", "--rank", "time", "--limit", "3"));
        String listed = out.toString();
        assertTrue(listed.contains("Recipe (ranked by time)"), listed);
        assertTrue(listed.contains("Shrimp tacos"), listed);
        String ranked = listed.substring(0, listed.indexOf("Left out"));
        assertFalse(ranked.contains("Sheet-pan salmon"), ranked);
        assertTrue(listed.contains("Sheet-pan salmon with sweet potato contains salmon (allergy: fish)."), listed);
        assertTrue(listed.contains("Per serving; cost is what you would buy beyond your pantry (+ means"), listed);
    }

    @Test
    void recipesOverTodaysTimeLimitAreMarked() {
        run("profile", "set", "--cook-minutes", "all=25");
        assertEquals(0, run("recipes", "list", "--meal", "dinner", "--include", "turkey"));
        String listed = out.toString();
        assertTrue(listed.contains(" 45*"), listed);
        assertTrue(listed.contains("* Over today's cooking-time limit of 25 minutes."), listed);
    }

    @Test
    void recipesListShowsCostAndRanksByBudget() {
        assertEquals(0, run("recipes", "list", "--meal", "snack", "--rank", "budget", "--limit", "2"));
        String listed = out.toString();
        assertTrue(listed.contains("Recipe (ranked by budget)"), listed);
        assertTrue(listed.matches("(?s).*cost\\n.*\\$\\d+\\.\\d{2}\\n.*"), listed);
    }

    @Test
    void recipesListWithNoMatch() {
        assertEquals(0, run("recipes", "list", "--include", "unobtainium"));
        assertTrue(out.toString().contains("No recipe fits"));
    }

    @Test
    void recipesShowGivesStepsNutritionAndProfileNotes() {
        run("profile", "set", "--allergies", "tree-nut");
        assertEquals(0, run("recipes", "show", "cottage cheese fruit plate", "--servings", "2"));
        String shown = out.toString();
        assertTrue(shown.contains("Cottage cheese fruit plate"), shown);
        assertTrue(shown.contains("15 g walnuts (optional)"), shown);
        assertTrue(shown.contains("Nutrition for 2 servings:"), shown);
        assertTrue(shown.contains("Note: Leave out optional walnuts (allergy: tree-nut)."), shown);
        assertTrue(shown.contains("not dietary advice"), shown);
    }

    @Test
    void unknownRecipeSuggestsNames() {
        assertEquals(1, run("recipes", "show", "tacos"));
        assertTrue(err.toString().contains("Did you mean: Black bean tacos, Shrimp tacos?"), err.toString());
    }

    @Test
    void recipesFindShowsTheAgentsChoiceReasonAndSteps() {
        run("profile", "set", "--goal", "gain");
        java.util.Map<String, Object> finish = new java.util.HashMap<>();
        finish.put("action", "finish");
        finish.put("tool", null);
        finish.put("arguments", "{}");
        finish.put("reason", "enough");
        llm.queue(java.util.Map.of("action", "call_tool", "tool", "recipeSearch",
                        "arguments", "{\"mealType\": \"dinner\"}", "reason", "dinners"), finish,
                java.util.Map.of("recipes", List.of("Shrimp tacos"), "rationale", "Quick and protein-rich."));

        assertEquals(0, run("recipes", "find", "quick high-protein dinner", "--max-minutes", "20", "--show-steps"));

        String shown = out.toString();
        assertTrue(shown.contains("Recommended"), shown);
        assertTrue(shown.contains("Shrimp tacos"), shown);
        assertTrue(shown.contains("Why: Quick and protein-rich."), shown);
        assertTrue(shown.contains("Agent steps:\n  1. recipeSearch {\"mealType\": \"dinner\"} -> "), shown);
        assertTrue(llm.calls().getFirst().prompt().contains("USER REQUEST: quick high-protein dinner"));
        assertTrue(llm.calls().getFirst().prompt().contains("at most 20 minutes"));
    }

    @Test
    void recipesFindReportsAnAiFailure() {
        run("profile", "set");
        llm.queue(new LLMException(LLMException.Kind.NOT_CONFIGURED, "no key"));
        assertEquals(1, run("recipes", "find", "dinner"));
        assertTrue(out.toString().contains("The AI agent is not set up: add LLM_API_KEY and LLM_MODEL to .env."),
                out.toString());
    }

    @Test
    void planGenerateAndShow() {
        run("profile", "set", "--calories", "1800");
        assertEquals(1, run("plan", "show"));
        assertTrue(out.toString().contains("No plan yet."));
        java.util.Map<String, Object> finish = new java.util.HashMap<>();
        finish.put("action", "finish");
        finish.put("tool", null);
        finish.put("arguments", "{}");
        finish.put("reason", "done");
        for (String type : List.of("breakfast", "lunch", "dinner")) {
            llm.queue(java.util.Map.of("action", "call_tool", "tool", "recipeSearch",
                    "arguments", "{\"mealType\": \"" + type + "\"}", "reason", type));
        }
        llm.queue(finish, java.util.Map.of("meals", List.of(
                java.util.Map.of("day", 1, "mealType", "breakfast", "recipe", "Overnight oats"),
                java.util.Map.of("day", 1, "mealType", "lunch", "recipe", "Lentil soup"),
                java.util.Map.of("day", 1, "mealType", "dinner", "recipe", "Shrimp tacos")),
                "rationale", "Light and varied.", "conflicts", List.of()));

        assertEquals(0, run("plan", "generate", "--days", "1", "--start", "2026-11-02"));
        String generated = out.toString();
        assertTrue(generated.contains("Plan saved."), generated);
        assertTrue(generated.contains("Plan 2026-11-02 to 2026-11-02 (1 day)"), generated);
        assertTrue(generated.contains("Mon 2026-11-02"), generated);
        assertTrue(generated.contains("  dinner     Shrimp tacos"), generated);
        assertTrue(generated.contains("(target 1800 kcal, -)"), generated);
        assertTrue(generated.indexOf("Why: Light and varied.") < generated.indexOf("not dietary advice"), generated);

        assertEquals(0, run("plan", "show"));
        assertTrue(out.toString().contains("  lunch      Lentil soup"), out.toString());
    }

    @Test
    void planGenerateRejectsBadDays() {
        assertEquals(1, run("plan", "generate", "--days", "30"));
        assertTrue(out.toString().contains("--days must be between 1 and 14."));
    }

    @Test
    void groceryNeedsAPlanFirst() {
        assertEquals(1, run("grocery", "generate"));
        assertTrue(err.toString().contains("No meal plan yet."), err.toString());
        assertEquals(1, run("grocery", "show"));
        assertTrue(out.toString().contains("No shopping list yet."));
    }

    @Test
    void groceryGenerateSubtractsThePantryAndPrices() {
        var soup = facade.findRecipe("Lentil soup");
        var plan = new io.github.ayech14.mealiq.domain.MealPlan(MealPlanningFacade.localProfileId("default"),
                LocalDate.of(2026, 11, 2), 1, List.of(new io.github.ayech14.mealiq.domain.PlannedMeal(
                        LocalDate.of(2026, 11, 2), io.github.ayech14.mealiq.domain.MealType.DINNER, soup, 4)));
        plan.status(io.github.ayech14.mealiq.domain.PlanStatus.ACTIVE);
        fixture.plans().save(plan);
        run("pantry", "set", "lentils", "1", "kg");

        assertEquals(0, run("grocery", "generate"));
        String listed = out.toString();
        assertTrue(listed.contains("  legume\n    lentils"), listed);
        assertTrue(listed.matches("(?s).*lentils\\s+250 g\\s+250 g\\s+-\\s+-.*"), listed);
        assertTrue(listed.contains("8 of 9 ingredients to buy. Estimated cost: $"), listed);
        assertTrue(listed.contains("No weekly budget is set"), listed);

        run("profile", "set", "--budget", "1");
        assertEquals(0, run("grocery", "optimize"));
        assertTrue(out.toString().contains("Budget for this plan: $0.14. Over by $"), out.toString());
        assertTrue(out.toString().contains("Note: Over budget by $"), out.toString());

        assertEquals(0, run("grocery", "show"));
        assertTrue(out.toString().contains("lentils"));
    }

    /** Saves an active one-day plan straight into the fake repository. */
    private void activePlan(String breakfast, String lunch, String dinner) {
        LocalDate day = LocalDate.of(2026, 11, 2);
        var plan = new io.github.ayech14.mealiq.domain.MealPlan(MealPlanningFacade.localProfileId("default"), day, 1,
                List.of(new io.github.ayech14.mealiq.domain.PlannedMeal(day,
                                io.github.ayech14.mealiq.domain.MealType.BREAKFAST, facade.findRecipe(breakfast), 1),
                        new io.github.ayech14.mealiq.domain.PlannedMeal(day,
                                io.github.ayech14.mealiq.domain.MealType.LUNCH, facade.findRecipe(lunch), 1),
                        new io.github.ayech14.mealiq.domain.PlannedMeal(day,
                                io.github.ayech14.mealiq.domain.MealType.DINNER, facade.findRecipe(dinner), 1)));
        plan.status(io.github.ayech14.mealiq.domain.PlanStatus.ACTIVE);
        fixture.plans().save(plan);
    }

    private void queueDinnerSwap(String recipe) {
        java.util.Map<String, Object> finish = new java.util.HashMap<>();
        finish.put("action", "finish");
        finish.put("tool", null);
        finish.put("arguments", "{}");
        finish.put("reason", "done");
        java.util.Map<String, Object> change = new java.util.HashMap<>();
        change.put("action", "replace");
        change.put("replacements", List.of(java.util.Map.of("day", 1, "mealType", "dinner", "recipe", recipe)));
        change.put("question", null);
        change.put("rationale", "Meat-free.");
        llm.queue(java.util.Map.of("action", "call_tool", "tool", "recipeSearch",
                "arguments", "{\"mealType\": \"dinner\"}", "reason", "dinners"), finish, change);
    }

    @Test
    void planModifyAppliesOnlyWhenTheUserSaysYes() {
        run("profile", "set");
        activePlan("Overnight oats", "Lentil soup", "Chicken stir-fry");

        queueDinnerSwap("Black bean tacos");
        input = "n\n";
        assertEquals(0, run("plan", "modify", "make dinner vegetarian"));
        String declined = out.toString();
        assertTrue(declined.contains("Proposed change:\n  Mon 2026-11-02 dinner: Chicken stir-fry -> Black bean tacos"),
                declined);
        assertTrue(declined.contains("Why: Meat-free."), declined);
        assertTrue(declined.contains("Apply this change? [y/N] Cancelled. The plan is unchanged."), declined);

        queueDinnerSwap("Black bean tacos");
        input = "y\n";
        assertEquals(0, run("plan", "modify", "make dinner vegetarian"));
        assertTrue(out.toString().contains("Applied: Replace 2026-11-02 dinner: Chicken stir-fry -> Black bean tacos. "
                + "Undo with: mealiq plan undo"), out.toString());
        assertTrue(out.toString().contains("Grocery list refreshed: "), out.toString());

        assertEquals(0, run("plan", "history"));
        assertTrue(out.toString().contains("1. Replace 2026-11-02 dinner"), out.toString());
        assertEquals(0, run("plan", "undo"));
        assertTrue(out.toString().contains("Undone: Replace 2026-11-02 dinner"), out.toString());
        assertEquals(1, run("plan", "undo"));
        assertTrue(out.toString().contains("There is no change to undo."));
    }

    @Test
    void planModifyWithYesSkipsTheQuestion() {
        run("profile", "set");
        activePlan("Overnight oats", "Lentil soup", "Chicken stir-fry");
        queueDinnerSwap("Black bean tacos");
        assertEquals(0, run("plan", "modify", "vegetarian dinner", "--yes"));
        assertFalse(out.toString().contains("Apply this change?"));
        assertTrue(out.toString().contains("Applied: "));
    }

    @Test
    void planSubstituteShowsTheVariant() {
        run("profile", "set");
        activePlan("Overnight oats", "Lentil soup", "Chicken stir-fry");
        llm.queue(java.util.Map.of("choices", List.of(java.util.Map.of("ingredient", "milk", "substitute",
                "soy milk")), "rationale", "Dairy-free."));
        assertEquals(0, run("plan", "substitute", "milk", "--yes"));
        String shown = out.toString();
        assertTrue(shown.contains("breakfast: Overnight oats -> Overnight oats (soy milk for milk)"), shown);
        assertTrue(shown.contains("Applied: Substitute 2026-11-02 breakfast: soy milk for milk in Overnight oats"),
                shown);
    }

    @Test
    void planChangesNeedAPlan() {
        run("profile", "set");
        assertEquals(1, run("plan", "modify", "anything"));
        assertTrue(err.toString().contains("No meal plan yet."), err.toString());
    }

    @Test
    void impactNoticesAndPlanAdapt() {
        run("profile", "set");
        activePlan("Overnight oats", "Lentil soup", "Sheet-pan salmon with sweet potato");

        assertEquals(0, run("profile", "set", "--allergies", "fish"));
        String saved = out.toString();
        assertTrue(saved.contains("This affects your meal plan:\n  Day 1 (2026-11-02) dinner: Sheet-pan salmon with "
                + "sweet potato contains salmon (allergy: fish).\nAdapt the plan with: mealiq plan adapt"), saved);

        assertEquals(0, run("plan", "show"));
        assertTrue(out.toString().contains("Adapt the plan with: mealiq plan adapt"));

        queueDinnerSwap("Black bean tacos");
        assertEquals(0, run("plan", "adapt", "--yes"));
        assertTrue(out.toString().contains("dinner: Sheet-pan salmon with sweet potato -> Black bean tacos"),
                out.toString());
        assertEquals(0, run("plan", "show"));
        assertFalse(out.toString().contains("This affects your meal plan"), out.toString());

        assertEquals(0, run("plan", "adapt"));
        assertTrue(out.toString().contains("Nothing to adapt"));
    }

    @Test
    void pantryChangesShowTheNotice() {
        run("profile", "set");
        run("pantry", "set", "rolled oats", "1", "kg");
        activePlan("Overnight oats", "Lentil soup", "Shrimp tacos");
        assertEquals(0, run("pantry", "remove", "rolled oats"));
        assertTrue(out.toString().contains("Overnight oats uses rolled oats, which is no longer in your pantry."),
                out.toString());
    }
}
