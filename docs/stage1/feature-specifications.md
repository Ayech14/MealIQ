# Detailed feature specifications

MealIQ has thirteen features, F01-F13. Every feature is available in the planned React GUI and the planned Python Typer CLI. GUI requests reach `MealPlanningFacade` through the FastAPI `MealPlanningApi`. CLI commands call the same facade in-process, so neither interface contains business logic.

**AI involvement** uses three categories:

- **Deterministic:** conventional code only.
- **AI-based:** the result comes from the LLM.
- **Hybrid:** the external LLM API (through `LLMClient`) interprets, ranks, or proposes. Deterministic services keep authority over calculations, validation, and saved state.

No MealIQ feature is purely AI-based: every LLM output is validated deterministically before it is shown or saved.

CLI command names below are the planned command surface for Stage 2.

## Summary

| ID | Feature | AI involvement | Use case | Sequence diagram |
|---|---|---|---|---|
| F01 | User goals and preferences | Deterministic | UC01 | SD04 (profile changes) |
| F02 | Pantry inventory management | Deterministic | UC02 | SD04 |
| F03 | Recipe search and recommendation | Hybrid | UC03 | SD05, SD01 |
| F04 | AI recipe generation | Hybrid | UC03 | SD05 |
| F05 | Weekly meal-plan generation | Hybrid | UC04 | SD01 |
| F06 | Nutrition analysis | Deterministic | UC05 | SD01, SD05 |
| F07 | Grocery-list generation | Deterministic | UC06 | SD02 |
| F08 | Grocery-list optimization | Hybrid | UC06, UC07 | SD02 |
| F09 | Ingredient substitution | Hybrid | UC07 | SD02, SD03 |
| F10 | Natural-language meal-plan modification | Hybrid | UC08 | SD03 |
| F11 | Cooking-time-aware planning | Hybrid | UC01, UC04, UC08 | SD01, SD03, SD05 |
| F12 | Budget-aware planning | Hybrid | UC01, UC04, UC06 | SD01, SD02 |
| F13 | Dynamic plan adaptation | Hybrid | UC09 | SD04 |

---

## F01 - User goals and preferences

| Field | Specification |
|---|---|
| Description | Records the weight goal (gain, lose, maintain), daily calorie and protein targets, dietary restrictions, allergies, disliked and preferred foods, meals per day, weekly grocery budget, and per-day cooking-time limits. |
| Why it is needed | Every planning decision depends on these constraints. Storing them once removes repeated re-entry and gives the agent a reliable, validated context. |
| GUI interaction | Profile and Constraints screen with form fields and a Save action. |
| CLI access | `mealiq profile show`, `mealiq profile set --goal gain --budget 100 --max-minutes mon=30 ...` |
| Input | `UserProfile` fields. |
| Output | Validated, saved profile, plus a notice when the active plan is affected. |
| AI involvement | Deterministic. |
| Expected workflow | `MealPlanningFacade.updateProfile()` calls `ProfileService.updateProfile()`, which validates and saves through `ProfileRepository`. It then publishes a `PlanRelevantChange` so that an affected plan can be adapted (F13). |
| Error / alternative cases | Invalid values (for example meals per day below 1, or a negative budget) are rejected with field-level messages. A preferred food that contains a declared allergen is flagged for the user to resolve; the allergy always takes precedence. |

## F02 - Pantry inventory management

| Field | Specification |
|---|---|
| Description | Adds, updates, removes, and lists pantry or fridge items, each with a quantity, a unit, and an optional expiry date. |
| Why it is needed | Planning and shopping both depend on what is already at home. Accurate stock avoids buying duplicates and lets plans use what is available. |
| GUI interaction | Pantry screen with an inventory table and add, edit, and remove actions. |
| CLI access | `mealiq pantry list`, `mealiq pantry add eggs 12 unit`, `mealiq pantry set eggs 0`, `mealiq pantry remove spinach` |
| Input | Ingredient, quantity, unit, optional expiry date. |
| Output | Current inventory, plus a plan-impact notice when relevant. |
| AI involvement | Deterministic. |
| Expected workflow | `PantryService.updateItem()` validates the quantity and unit, updates `Pantry`, saves it through `PantryRepository`, and publishes `PlanRelevantChange(PANTRY_CHANGED)` to the registered observers. |
| Error / alternative cases | Negative quantities and unknown units are rejected. Expired items stay visible but are excluded from `Pantry.availableQuantity()`. |

## F03 - Recipe search and recommendation

| Field | Specification |
|---|---|
| Description | Retrieves recipes and ranks them by fit with pantry stock, meal type, restrictions, nutrition goal, cooking time, and cost. |
| Why it is needed | Users need options that fit all of their constraints together, not just keyword matches. Ranking explains why each recipe is suggested. |
| GUI interaction | Recipe browser with filters (meal type, maximum minutes, ingredients to include or exclude) and a ranked result list with explanations. |
| CLI access | `mealiq recipes find "quick high-protein dinner" --max-minutes 20` |
| Input | `RecipeQuery` together with the user's profile and pantry. |
| Output | Ranked compatible recipes, with the reason each was chosen and any excluded or near-match recipes. |
| AI involvement | Hybrid. Retrieval and filtering are deterministic. The agent ranks the candidates (through the selected `PlanningStrategy`) and writes the explanation. |
| Expected workflow | `AgentController.recommendRecipe()` builds a `PlanningContext`. `MealPlanningAgent.recommendRecipes()` calls `RecipeTool` through `ToolManager`. `RecipeTool.searchRecipes()` queries `RecipeRepository` and, when local results are insufficient, `ExternalRecipeAdapter`. The agent then calls `rankRecipes()`, and every returned recipe is checked by `ConstraintValidator.validateRestrictions()` (SD05). |
| Error / alternative cases | If no recipe fits exactly, near matches are returned with the constraint each violates, or generation (F04) is offered. If the external provider fails, results are limited to the local repository and the user is told. |

## F04 - AI recipe generation

| Field | Specification |
|---|---|
| Description | Generates a custom, structured recipe (ingredients, quantities, steps, minutes, servings) when retrieval finds nothing suitable or the user asks for one. |
| Why it is needed | Curated recipe data cannot cover every combination of pantry stock, restrictions, and time limits. Generation fills those gaps without breaking hard constraints. |
| GUI interaction | "Generate a recipe" action in the recipe browser, with an editable preview before saving. |
| CLI access | `mealiq recipes generate --meal dinner --max-minutes 20 --use chicken,rice` |
| Input | Meal type, constraints, and ingredients to use, plus the profile. |
| Output | A candidate `Recipe` (`source = GENERATED`) with a calculated `NutritionSummary`. |
| AI involvement | Hybrid. The LLM produces the recipe. Deterministic code checks its structure, restrictions, and nutrition. |
| Expected workflow | `MealPlanningAgent.generateRecipe()` uses `PromptBuilder.buildRecipePrompt()` and `LLMClient.generateStructured()` (implemented by `ProviderLLMAdapter`) with a recipe schema. `ConstraintValidator.validateRestrictions()` and `NutritionService.calculateNutrition()` validate the result before it is displayed (SD05). |
| Error / alternative cases | Malformed output is retried once, then declined with a clear message. A recipe that breaks an allergy or restriction is rejected and never shown as compliant. |

## F05 - Weekly meal-plan generation

| Field | Specification |
|---|---|
| Description | Creates a multi-day plan with the requested number of meals per day, balancing the nutrition goal, pantry use, budget, cooking time, and preferences. |
| Why it is needed | This is the core multi-constraint task that users find hard to do by hand. It requires planning across many slots rather than picking one recipe at a time. |
| GUI interaction | Weekly calendar with a "Generate plan" action (start date, number of days, planning mode). The result shows meals, rationale, and warnings. |
| CLI access | `mealiq plan generate --start 2026-10-12 --days 7 --mode nutrition` |
| Input | `PlanRequest` (start date, days, `PlanningMode`) together with the profile and pantry. |
| Output | A saved `MealPlan` with rationale and nutrition totals, or a conflict report with alternatives. |
| AI involvement | Hybrid. The agent retrieves and ranks candidates and proposes a `PlanningProposal`. Deterministic validators decide whether the plan is accepted. |
| Expected workflow | `AgentController.generateWeeklyPlan()` builds the context. `MealPlanningAgent.createPlanProposal()` gets a strategy from `PlanningStrategyFactory`. It then runs its tool loop: the LLM chooses each tool call (for example `recipeSearch`), `ToolManager` validates and executes it, and the strategy ranks the returned candidates. Finally the LLM produces a structured proposal. `ProposalValidator` and `ConstraintValidator.validatePlan()` (with `NutritionService` and `BudgetService`) check it, and `MealPlanService.createFromProposal()` saves it. If validation fails, the violations are fed back for at most one retry (SD01). |
| Error / alternative cases | Conflicting constraints produce a `CONFLICT` response with prioritized trade-offs and nothing is saved. A plan over budget or over a time limit is never saved silently. |

## F06 - Nutrition analysis

| Field | Specification |
|---|---|
| Description | Calculates calories, protein, carbohydrates, and fat for a recipe at a given number of servings, for a set of meals, and per day for a plan. |
| Why it is needed | Users plan toward nutrition goals, and the agent's proposals must be checked with real arithmetic rather than LLM estimates. |
| GUI interaction | Nutrition panel on recipe and plan views, with daily and weekly totals. |
| CLI access | `mealiq nutrition plan <planId>`, `mealiq nutrition recipe <recipeId> --servings 2` |
| Input | Recipe and servings, a list of meals, or a meal plan. |
| Output | `NutritionSummary` (totals and a `complete` flag), or daily totals. |
| AI involvement | Deterministic. |
| Expected workflow | `MealPlanningFacade.analyzeNutrition()` calls `NutritionService.calculateNutrition()` / `dailyTotals()`. The agent reaches the same service through `NutritionTool`, and `ConstraintValidator` uses it during plan validation (SD01, SD05). |
| Error / alternative cases | Missing ingredient nutrition data produces a partial result labelled incomplete; values are never invented. |

## F07 - Grocery-list generation

| Field | Specification |
|---|---|
| Description | Works out which ingredients, and how much of each, must be bought for a selected plan after subtracting usable pantry stock. |
| Why it is needed | Turning a plan into a shopping list by hand is error-prone, especially with partial quantities and expired stock. |
| GUI interaction | Grocery screen with a "Generate list" action for the active plan. |
| CLI access | `mealiq grocery generate --plan <planId>` |
| Input | `MealPlan` and the `Pantry` snapshot. |
| Output | `GroceryList` of shortages (required, in pantry, and to-buy quantities). |
| AI involvement | Deterministic. |
| Expected workflow | `GroceryService.generateList()` loops over each meal's `IngredientRequirement`, calls `Pantry.availableQuantity()`, and records the shortfall in a `GroceryItem` (SD02). |
| Error / alternative cases | Incompatible units or an unmapped ingredient stop that item's calculation and ask the user to resolve it; no unsafe conversion is made. |

## F08 - Grocery-list optimization

| Field | Specification |
|---|---|
| Description | Combines duplicate needs across meals, estimates prices, compares the total with the budget, and, when over budget, proposes cheaper alternatives. |
| Why it is needed | Avoids unnecessary purchases and makes the budget consequence of a plan visible before shopping. |
| GUI interaction | "Optimize" action on the grocery screen, showing the estimate, budget status, and any proposed alternatives to preview. |
| CLI access | `mealiq grocery optimize --plan <planId>` |
| Input | Raw `GroceryList`, price data, and the weekly budget. |
| Output | Combined `GroceryList`, `BudgetStatus` (estimate, shortfall, cost drivers), and optional `PlanChange` alternatives. |
| AI involvement | Hybrid. Combining items, pricing, and budget evaluation are deterministic. Choosing cheaper substitutions is agent-assisted. |
| Expected workflow | `GroceryService.optimizeList()` calls `consolidate()` and `PriceDataSource.estimatePrice()`. `BudgetService.evaluate()` returns the `BudgetStatus`. If over budget, `AgentController.resolveBudgetConflict()` asks `MealPlanningAgent.proposeSubstitution()` for alternatives through `SubstitutionTool`, and `ConstraintValidator.validateChange()` recalculates the cost (SD02). |
| Error / alternative cases | A missing price gives a partial estimate with a warning. An unavoidable shortfall is reported with its exact amount; the list is never claimed to be within budget without recalculation. |

## F09 - Ingredient substitution

| Field | Specification |
|---|---|
| Description | Replaces an unavailable, disliked, unsuitable, or too-expensive ingredient with a compatible alternative. |
| Why it is needed | Plans break when ingredients run out or constraints change. Safe substitution keeps the plan usable without breaking restrictions. |
| GUI interaction | "Substitute" action on an ingredient in a recipe or plan, with a candidate list and a preview before confirming. |
| CLI access | `mealiq plan substitute <planId> --ingredient mushrooms` |
| Input | Target ingredient, the plan or recipe, and the profile constraints. |
| Output | Validated `PlanChange` (`SUBSTITUTE_INGREDIENT`) to preview, then the updated plan after confirmation. |
| AI involvement | Hybrid. `SubstitutionTool` supplies catalogue candidates deterministically. The agent chooses and explains. Validators enforce the hard constraints. |
| Expected workflow | `AgentController.requestSubstitution()` calls `MealPlanningAgent.proposeSubstitution()`, which uses `SubstitutionTool.findCandidates()` (backed by `IngredientRepository.findSubstitutes()`). `ConstraintValidator.validateChange()` checks restriction, time, and budget effects. After confirmation, `SubstituteIngredientCommand.execute()` applies the change (SD02, SD03). |
| Error / alternative cases | If no valid substitute exists, the recipe is left unchanged and the user gets options. Cancelling leaves the plan unchanged. |

## F10 - Natural-language meal-plan modification

| Field | Specification |
|---|---|
| Description | Applies typed requests such as "Make Wednesday's dinner vegetarian" or "Replace Friday's dinner with something cheaper" to the active plan. |
| Why it is needed | Editing a plan in ordinary language is faster than navigating forms and is a core agent capability: interpretation plus a safe structured change. |
| GUI interaction | Plan chat box beside the calendar. The proposed change is shown as a before/after preview with Confirm and Cancel. |
| CLI access | `mealiq plan modify <planId> "make Wednesday's dinner vegetarian"` (confirmation prompt), `mealiq plan undo <planId>` |
| Input | Instruction text and the active `MealPlan`. |
| Output | `PlanChange` preview, a clarification question, or alternatives. After confirmation, the updated plan and refreshed grocery list. |
| AI involvement | Hybrid. The LLM interprets the instruction into a structured `PlanChange`. Commands apply the change deterministically and can undo it. |
| Expected workflow | `AgentController.interpretModification()` calls `MealPlanningAgent.proposeChange()`, which recalls prior decisions from `ConversationMemory`, builds a prompt, and calls `LLMClient.generateStructured()`. `ProposalValidator.validate()` and `ConstraintValidator.validateChange()` check the change. After confirmation, `AgentController.confirmChange()` calls `MealPlanService.executeChange()`, which creates and executes a `PlanChangeCommand`. It also records the decision and calls `GroceryService.refreshForPlan()` (SD03). |
| Error / alternative cases | An ambiguous target produces a clarification question. A provider failure after one retry returns `FAILED` and the plan is unchanged. An infeasible change returns alternatives. Cancelling or not confirming changes nothing. `undoLastChange()` reverts the last confirmed command. |

## F11 - Cooking-time-aware planning

| Field | Specification |
|---|---|
| Description | Treats per-day cooking-time limits (for example Monday 30 minutes, Wednesday 15 minutes) as planning constraints, and adapts meals when available time changes. |
| Why it is needed | Time is often the constraint that makes a plan unusable on busy days. Treating it explicitly avoids plans that cannot be cooked. |
| GUI interaction | Time-limit controls per day in the profile and on the calendar. Each meal's minutes are shown, and any meal over the limit is highlighted. |
| CLI access | `mealiq profile set --max-minutes wed=15`, or `mealiq plan modify <planId> "I only have 20 minutes tonight"` |
| Input | Per-day maximum minutes in `UserProfile`, plus recipe preparation and cooking minutes. |
| Output | A plan in which every meal fits its day's limit, or an explicit time conflict with quick alternatives. |
| AI involvement | Hybrid. `TimeAwareStrategy` and the agent prefer fast candidates. `ConstraintValidator.validateTime()` enforces the limit deterministically. |
| Expected workflow | Time limits come from UC01. `TimeAwareStrategy.rank()` orders candidates in UC04 and UC03. `validateTime()` runs in `validatePlan()` / `validateChange()` (SD01, SD03, SD05). A changed limit triggers adaptation through `UpdatePlanConstraintCommand` (F13). |
| Error / alternative cases | If no recipe fits a day's limit, the conflict is reported with the fastest available options; the limit is never silently exceeded. |

## F12 - Budget-aware planning

| Field | Specification |
|---|---|
| Description | Uses the weekly budget when selecting recipes, generating the plan and grocery list, and choosing substitutions. |
| Why it is needed | A plan the user cannot afford is not useful. Budget trade-offs must be visible rather than ignored. |
| GUI interaction | Budget field in the profile, and budget status (estimate, shortfall) on the plan and grocery screens. |
| CLI access | `mealiq profile set --budget 60`, `mealiq grocery optimize --plan <planId>` |
| Input | Weekly budget, price estimates, and plan meals. |
| Output | Plan and list estimates with `BudgetStatus`, plus trade-off explanations when over budget. |
| AI involvement | Hybrid. `BudgetStrategy` and the agent rank cheaper options. `BudgetService` calculates and enforces the budget. |
| Expected workflow | `BudgetStrategy.rank()` is selected for budget-first planning. `BudgetService.estimateCost()` checks the proposal during `validatePlan()` (SD01). `BudgetService.evaluate()` produces the final `BudgetStatus` for the grocery list, and over-budget results lead to F08 alternatives (SD02). |
| Error / alternative cases | An impossible budget reports the exact shortfall and suggested compromises. Missing prices are labelled as partial estimates. |

## F13 - Dynamic plan adaptation

| Field | Specification |
|---|---|
| Description | Detects when a pantry, profile, budget, or time change affects the active plan, and proposes the smallest set of changes to keep the plan valid. |
| Why it is needed | Real plans go out of date ("I ran out of eggs", "my budget dropped to $60"). Regenerating the whole week would discard choices the user still wants. |
| GUI interaction | An impact notice after a pantry or profile change, listing affected meals with a proposed adaptation to preview and confirm. An explicit "Adapt plan" action is also available. |
| CLI access | Shown automatically after `mealiq pantry set ...` / `mealiq profile set ...`. `mealiq plan adapt <planId>` |
| Input | `PlanRelevantChange` event and the active plan. |
| Output | `PlanImpact` (affected meals and grocery items), a minimal `PlanChange` to preview, and, after confirmation, the adapted plan and refreshed grocery list. |
| AI involvement | Hybrid. Impact detection and grocery staleness are deterministic. The adaptation itself is proposed by the agent and validated. |
| Expected workflow | `PantryService` / `ProfileService.publish()` notifies `GroceryListImpactObserver`, which calls `GroceryService.markAffectedListStale()`, and `MealPlanImpactObserver`, which calls `MealPlanService.assessImpact()` and records the impact with `recordImpact()`. The pantry update returns at once with an impact notice. When the user chooses to adapt, `MealPlanningFacade.adaptPlan()` calls `AgentController.adaptPlan()`. The agent calls `proposeAdaptation()`, choosing replacement candidates through its tool loop, and the change is validated with `validateChange()`. It is held as pending until the user confirms through `confirmChange()` (SD04). |
| Error / alternative cases | If no valid adaptation exists, the current plan is kept and marked unresolved with its conflicts. The grocery list stays marked stale until it is refreshed. |
