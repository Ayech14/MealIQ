# Use cases

**Actors.**

- **Meal Planner** (primary): the individual who plans meals and groceries.
- **External LLM API** («system», supporting): proposes plans, recipes, and changes. Supporting actors provide services to MealIQ but never start a use case.
- **Recipe / Price Data Provider** («system», supporting): supplies external recipes and indicative prices.

The React GUI and the Typer CLI are boundary components inside the system, not actors. GUI requests reach `MealPlanningFacade` through the FastAPI `MealPlanningApi`; CLI commands call the facade in-process.

**Relationships (shown in the [use-case diagram](diagrams/png/use-case-diagram.png)).**

- UC04 «include»s UC03, because planning always retrieves and ranks recipes.
- UC04 «include»s UC05, because every proposed plan gets a deterministic nutrition summary.
- UC07 «extend»s UC06 when `BudgetStatus` reports the estimate is over budget.
- UC09 «extend»s UC01 and UC02 when a saved profile or pantry change affects the active plan.

The Meal Planner can also start UC03, UC05, UC07, and UC09 directly.

| Use case | Related features | Sequence diagram |
|---|---|---|
| UC01 Configure profile and constraints | F01, F11, F12, F13 | SD04 (profile-change path, noted) |
| UC02 Manage pantry | F02, F13 | SD04 |
| UC03 Find or generate a recipe | F03, F04, F06, F09, F11, F12 | SD05 |
| UC04 Generate weekly meal plan | F01, F03-F06, F11, F12 | SD01 |
| UC05 Analyze nutrition | F06 | SD01, SD05 (`NutritionService` calls) |
| UC06 Generate and optimize grocery list | F02, F07, F08, F12 | SD02 |
| UC07 Substitute ingredient or resolve budget conflict | F08, F09, F11, F12 | SD02 (budget path), SD03 (confirmation path) |
| UC08 Modify meal plan in natural language | F06-F12 | SD03 |
| UC09 Adapt affected plan | F02, F09-F13 | SD04 |

UC01 and UC05 are single-service operations: the facade makes one validated service call. They share the boundary and facade structure shown in SD04 and SD01, so they have no separate sequence diagram.

---

## UC01 - Configure profile and constraints

| Field | Description |
|---|---|
| Primary actor | Meal Planner |
| Goal | Save planning goals, nutrition targets, dietary restrictions, allergies, likes and dislikes, meals per day, weekly budget, and per-day cooking-time limits. |
| Preconditions | MealIQ is running. |
| Trigger | The user saves the Profile and Constraints screen or runs `mealiq profile set`. |
| Main success scenario | 1. The user enters or edits profile fields.<br>2. The interface sends them to `MealPlanningFacade.updateProfile()`.<br>3. `ProfileService.updateProfile()` validates ranges and checks for contradictions within the profile.<br>4. `ProfileRepository.save()` stores the `UserProfile`.<br>5. `ProfileService.publish()` emits a `PlanRelevantChange` (`PROFILE_CHANGED`, `BUDGET_CHANGED`, or `TIME_LIMIT_CHANGED`).<br>6. The interface shows the saved profile. |
| Alternative / exception flows | 3a. A value is invalid (for example a negative budget): the field is rejected with a message, nothing is saved, and the flow returns to step 1.<br>3b. A preferred food contains a declared allergen: the conflict is flagged, the allergy takes precedence, and the user is asked to resolve it.<br>5a. The change affects the active plan: UC09 extends this use case and an impact notice is shown. |
| Postconditions | The validated profile is saved, and observers have been told about the change. |
| Related features | F01, F11, F12, F13 |

## UC02 - Manage pantry

| Field | Description |
|---|---|
| Primary actor | Meal Planner |
| Goal | Keep an accurate record of ingredients available at home. |
| Preconditions | A profile exists. |
| Trigger | The user adds, edits, removes, or lists a pantry item. |
| Main success scenario | 1. The user submits `PantryItem` data (ingredient, quantity, unit, optional expiry).<br>2. `MealPlanningFacade.updatePantry()` calls `PantryService.updateItem()`.<br>3. The service validates quantity and unit and updates `Pantry`.<br>4. `PantryRepository.save()` stores the pantry.<br>5. `PantryService.publish()` emits `PlanRelevantChange(PANTRY_CHANGED)` to `GroceryListImpactObserver` and `MealPlanImpactObserver`, which only record the impact (no LLM call).<br>6. The interface shows the updated inventory. |
| Alternative / exception flows | 3a. Negative quantity or unknown unit: the update is rejected and nothing changes.<br>3b. The item has expired: it stays visible but is excluded from available stock.<br>5a. The change affects the active plan: an impact notice is shown, and UC09 extends this use case if the user chooses to adapt. |
| Postconditions | The pantry is current and observers have been notified. |
| Related features | F02, F13 |

## UC03 - Find or generate a recipe

| Field | Description |
|---|---|
| Primary actor | Meal Planner |
| Supporting actors | Recipe / Price Data Provider, External LLM API |
| Goal | Receive compatible recipe recommendations, or a custom generated recipe. |
| Preconditions | A profile exists. A recipe source and an LLM adapter are configured. |
| Trigger | The user searches recipes, asks for a recommendation, or asks for a generated recipe. |
| Main success scenario | 1. The user submits criteria (`RecipeQuery`).<br>2. `AgentController.recommendRecipe()` builds a `PlanningContext` from the profile and pantry.<br>3. `MealPlanningAgent.recommendRecipes()` calls `RecipeTool` through `ToolManager`.<br>4. `RecipeTool.searchRecipes()` queries `RecipeRepository`.<br>5. The agent ranks the candidates with the strategy from `PlanningStrategyFactory`.<br>6. For each returned recipe, `ConstraintValidator.validateRestrictions()` checks allergens, dislikes, and time, and `NutritionService.calculateNutrition()` adds nutrition.<br>7. The interface shows the ranked recipes with rationale, time, cost, and nutrition. |
| Alternative / exception flows | 4a. Too few local matches: `ExternalRecipeAdapter` queries the external provider. If the provider fails, only local results are used and the user is told.<br>5a. No compatible candidate, or a custom recipe was requested: `generateRecipe()` asks the LLM for a schema-constrained recipe through `PromptBuilder` and `ProviderLLMAdapter`, then continues at step 6.<br>6a. A generated recipe breaks a restriction or is malformed after one retry: it is rejected and the trade-offs are explained. |
| Postconditions | Only validated recipes are shown. No plan state changes. |
| Related features | F03, F04, F06, F09, F11, F12 |

## UC04 - Generate weekly meal plan

| Field | Description |
|---|---|
| Primary actor | Meal Planner |
| Supporting actors | External LLM API, Recipe / Price Data Provider |
| Goal | Create a multi-day plan with the requested meals per day that satisfies the user's constraints. |
| Preconditions | A profile exists. A planning period and mode are supplied. |
| Trigger | The user selects "Generate plan" or runs `mealiq plan generate`. |
| Main success scenario | 1. The interface sends a `PlanRequest` to `MealPlanningFacade.generateWeeklyPlan()`.<br>2. `AgentController` builds the `PlanningContext` (profile, pantry, active plan, budget, time limits).<br>3. `MealPlanningAgent.createPlanProposal()` gets a `PlanningStrategy` from `PlanningStrategyFactory`.<br>4. In a bounded tool loop, the LLM chooses the next tool call (for example `recipeSearch`, which includes UC03). `ToolManager.isValidCall()` checks it, `ToolManager.execute()` runs it, and the strategy ranks the returned candidates before they are fed back.<br>5. The agent asks the LLM for a structured `PlanningProposal`.<br>6. `ProposalValidator` checks the structure, and `ConstraintValidator.validatePlan()` checks restrictions and time, nutrition (includes UC05), and estimated cost.<br>7. `MealPlanService.createFromProposal()` saves the `MealPlan`.<br>8. The interface shows the calendar, rationale, nutrition totals, and warnings. |
| Alternative / exception flows | 4a. A slot has no compatible recipe: the LLM asks for a generated recipe (UC03 step 5a).<br>4b. The LLM requests an unknown tool or invalid arguments: the call is not executed, and the error is returned to the LLM for its next step.<br>6a. Validation fails: the violations are added to the context and steps 3-6 repeat once.<br>6b. Still invalid after the retry: a `CONFLICT` response lists the violations and trade-offs, and no plan is saved.<br>5a. The LLM provider is unavailable after its retry: a `FAILED` response is returned and no plan is saved.<br>2a. Critical information is missing (for example meals per day): the user is asked to complete the profile. |
| Postconditions | Either a validated plan is saved and active, or nothing is saved and an explicit conflict report is shown. |
| Related features | F01, F03, F04, F05, F06, F11, F12 |

## UC05 - Analyze nutrition

| Field | Description |
|---|---|
| Primary actor | Meal Planner |
| Goal | See calorie and macronutrient totals for a recipe, a set of meals, or a plan. |
| Preconditions | The target recipe or plan exists. |
| Trigger | The user opens the nutrition panel or runs `mealiq nutrition`. |
| Main success scenario | 1. The user selects a recipe or plan.<br>2. `MealPlanningFacade.analyzeNutrition()` calls `NutritionService.calculateNutrition()` / `dailyTotals()`.<br>3. The service totals the stored ingredient nutrition values.<br>4. The interface shows the totals with a completeness indicator. |
| Alternative / exception flows | 3a. Some ingredient nutrition data is missing: the partial total is labelled incomplete; values are never estimated by the LLM. |
| Postconditions | No state changes. |
| Related features | F06 |

## UC06 - Generate and optimize grocery list

| Field | Description |
|---|---|
| Primary actor | Meal Planner |
| Supporting actors | Recipe / Price Data Provider |
| Goal | Get a combined, pantry-aware shopping list with a budget estimate. |
| Preconditions | An active plan and a pantry exist. |
| Trigger | The user selects "Generate" or "Optimize" on the grocery screen. |
| Main success scenario | 1. `MealPlanningFacade.generateOptimizedGroceryList()` loads the plan (`MealPlanService.getPlan()`) and pantry (`PantryService.getPantry()`).<br>2. `GroceryService.generateList()` subtracts `Pantry.availableQuantity()` from each requirement.<br>3. `GroceryService.optimizeList()` combines duplicate items and calls `PriceDataSource.estimatePrice()` for each item.<br>4. `GroceryListRepository.save()` stores the list.<br>5. `BudgetService.evaluate()` returns the `BudgetStatus`.<br>6. The interface shows the list, estimate, and warnings. |
| Alternative / exception flows | 2a. Incompatible units: the item is flagged for the user to resolve, and the rest of the list is produced.<br>3a. A price is unavailable: the estimate is marked partial.<br>5a. The estimate is over budget: UC07 extends this use case and proposes cheaper alternatives. |
| Postconditions | The grocery list is saved with its estimate and budget status. |
| Related features | F02, F07, F08, F12 |

## UC07 - Substitute ingredient or resolve budget conflict

| Field | Description |
|---|---|
| Primary actor | Meal Planner |
| Supporting actors | External LLM API |
| Goal | Replace an ingredient with a safe, compatible alternative, or bring the plan back within budget. |
| Preconditions | An active plan exists, together with either a target ingredient or an over-budget `BudgetStatus`. |
| Trigger | The user selects "Substitute", or UC06 detects that the estimate is over budget. |
| Main success scenario | 1. `AgentController.requestSubstitution()` (user-initiated) or `resolveBudgetConflict()` (from UC06) builds the context.<br>2. `MealPlanningAgent.proposeSubstitution()` calls `SubstitutionTool.findCandidates()`, which uses `IngredientRepository.findSubstitutes()`.<br>3. The agent selects and explains substitutions and returns a `PlanChange`.<br>4. `ConstraintValidator.validateChange()` checks restriction, nutrition, time, and recalculated cost effects.<br>5. The interface previews the change.<br>6. The user confirms. `AgentController.confirmChange()` calls `MealPlanService.executeChange()`, which runs a `SubstituteIngredientCommand`, and the grocery list is refreshed. |
| Alternative / exception flows | 2a. No catalogue candidate exists: the options and remaining shortfall are reported and nothing changes.<br>4a. The change fails validation: it is rejected and alternatives are offered.<br>6a. The user cancels: no command runs and the plan is unchanged. |
| Postconditions | The confirmed substitution is applied and the list recalculated, or the original plan is unchanged. |
| Related features | F08, F09, F11, F12 |

## UC08 - Modify meal plan in natural language

| Field | Description |
|---|---|
| Primary actor | Meal Planner |
| Supporting actors | External LLM API |
| Goal | Change a specific meal or constraint by typing an ordinary instruction. |
| Preconditions | An active plan exists. |
| Trigger | The user types an instruction such as "Make Wednesday's dinner vegetarian". |
| Main success scenario | 1. `MealPlanningFacade.modifyPlan()` loads the plan and calls `AgentController.interpretModification()`.<br>2. `MealPlanningAgent.proposeChange()` recalls prior decisions from `ConversationMemory` and builds a prompt with `PromptBuilder`.<br>3. `LLMClient.generateStructured()` returns a structured `PlanChange`.<br>4. `ProposalValidator.validate()` and `ConstraintValidator.validateChange()` check the target and constraints.<br>5. The interface previews the change.<br>6. The user confirms. `AgentController.confirmChange()` calls `MealPlanService.executeChange()`, which creates and executes a `PlanChangeCommand` and saves the plan.<br>7. The decision is recorded in `ConversationMemory`, and `GroceryService.refreshForPlan()` updates the list.<br>8. The interface shows the updated plan, nutrition, and list. |
| Alternative / exception flows | 3a. The LLM output is malformed or the provider fails: the request is retried once, then a `FAILED` response is returned and the plan is unchanged.<br>4a. The target is ambiguous (for example "dinner" without a day): a clarification question is returned.<br>4b. The change breaks a hard constraint: a `CONFLICT` response offers alternatives.<br>6a. The user cancels: no command runs.<br>6b. The user later runs undo: `MealPlanService.undoLastChange()` reverts the last command. |
| Postconditions | An auditable, reversible change is applied and the grocery list refreshed, or the plan is unchanged. |
| Related features | F06, F07, F08, F09, F10, F11, F12 |

## UC09 - Adapt affected plan

| Field | Description |
|---|---|
| Primary actor | Meal Planner |
| Supporting actors | External LLM API |
| Goal | Revise only the parts of the plan affected by an inventory, preference, time, or budget change. |
| Preconditions | An active plan exists, and a plan-relevant change has occurred (or the user asks to adapt). |
| Trigger | A `PlanRelevantChange` event from UC01 or UC02, or the user running `mealiq plan adapt`. |
| Main success scenario | 1. The publisher notifies its observers.<br>2. `GroceryListImpactObserver` calls `GroceryService.markAffectedListStale()`.<br>3. `MealPlanImpactObserver` calls `MealPlanService.assessImpact()` and records the `PlanImpact` with `recordImpact()`. The original update returns immediately.<br>4. The interface shows the affected meals with an "Adapt plan" option, and the user chooses it.<br>5. `MealPlanningFacade.adaptPlan()` reads the pending impact and calls `AgentController.adaptPlan()`. `MealPlanningAgent.proposeAdaptation()` uses its tool loop (for example `recipeSearch` for replacements) to propose a minimal `PlanChange`.<br>6. `ConstraintValidator.validateChange()` checks the change, which is held as pending and previewed.<br>7. The user confirms. The change is applied as in UC08 steps 6-7, and the grocery list is refreshed. |
| Alternative / exception flows | 3a. The impact is empty: nothing is recorded or shown.<br>4a. The user does not choose to adapt: the impact stays recorded and the plan is flagged as affected.<br>5a. No feasible adaptation exists: the plan is kept, marked unresolved, and its conflicts are listed.<br>7a. The user declines: the plan stays as it is and the grocery list remains marked stale until refreshed. |
| Postconditions | Affected meals are updated after confirmation, or are marked unresolved. Unaffected meals are never changed. |
| Related features | F02, F09, F10, F11, F12, F13 |
