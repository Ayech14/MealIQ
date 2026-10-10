# Feature-to-design traceability and realization

Each feature traces from requirement to use case, classes, methods, sequence diagram, and design patterns. Every class and method named here appears in the [class diagrams](diagrams/png/class-diagram-complete.png), and every sequence diagram call uses an operation defined there.

## Traceability table

| Feature | Description | Type | Related use case | Classes | Key methods | Sequence diagram | Design pattern(s) |
|---|---|---|---|---|---|---|---|
| F01 | User goals and preferences | Deterministic | UC01 | `MealPlanningFacade`, `ProfileService`, `UserProfile`, `ProfileRepository` | `updateProfile()`, `save()`, `publish()` | SD04 (profile-change path) | Facade, Observer |
| F02 | Pantry inventory management | Deterministic | UC02 | `MealPlanningFacade`, `PantryService`, `Pantry`, `PantryItem`, `PantryRepository` | `updatePantry()`, `updateItem()`, `availableQuantity()`, `publish()` | SD04 | Facade, Observer |
| F03 | Recipe search and recommendation | Hybrid | UC03 | `AgentController`, `MealPlanningAgent`, `ToolManager`, `RecipeTool`, `RecipeRepository`, `ExternalRecipeAdapter`, `PlanningStrategy` | `recommendRecipe()`, `recommendRecipes()`, `searchRecipes()`, `find()`, `rankRecipes()`, `rank()` | SD05, SD01 | Strategy, Factory Method, Adapter, Facade |
| F04 | AI recipe generation | Hybrid | UC03 | `MealPlanningAgent`, `PromptBuilder`, `LLMClient`, `ProviderLLMAdapter`, `ConstraintValidator`, `NutritionService` | `generateRecipe()`, `buildRecipePrompt()`, `generateStructured()`, `validateRestrictions()`, `calculateNutrition()` | SD05 | Adapter, Facade |
| F05 | Weekly meal-plan generation | Hybrid | UC04 | `AgentController`, `MealPlanningAgent`, `PlanningStrategyFactory`, `PromptBuilder`, `ToolManager`, `ProposalValidator`, `ConstraintValidator`, `MealPlanService`, `MealPlanRepository` | `generateWeeklyPlan()`, `createPlanProposal()`, `strategyFor()`, `createStrategy()`, `buildToolSelectionPrompt()`, `isValidCall()`, `execute()`, `validatePlan()`, `createFromProposal()` | SD01 | Strategy, Factory Method, Facade, Adapter |
| F06 | Nutrition analysis | Deterministic | UC05 | `NutritionService`, `NutritionTool`, `NutritionSummary`, `ConstraintValidator` | `calculateNutrition()`, `dailyTotals()`, `analyze()` | SD01, SD05 | Facade |
| F07 | Grocery-list generation | Deterministic | UC06 | `GroceryService`, `Pantry`, `GroceryList`, `GroceryItem`, `GroceryTool` | `generateList()`, `availableQuantity()`, `project()` | SD02 | Facade |
| F08 | Grocery-list optimization | Hybrid | UC06, UC07 | `GroceryService`, `PriceDataSource`, `BudgetService`, `AgentController`, `MealPlanningAgent`, `SubstitutionTool` | `optimizeList()`, `consolidate()`, `estimatePrice()`, `evaluate()`, `resolveBudgetConflict()`, `proposeSubstitution()` | SD02 | Adapter, Facade |
| F09 | Ingredient substitution | Hybrid | UC07 | `SubstitutionTool`, `IngredientRepository`, `MealPlanningAgent`, `ConstraintValidator`, `SubstituteIngredientCommand` | `requestSubstitution()`, `proposeSubstitution()`, `findCandidates()`, `validateChange()`, `execute()` | SD02, SD03 | Command, Facade |
| F10 | Natural-language meal-plan modification | Hybrid | UC08 | `AgentController`, `MealPlanningAgent`, `ConversationMemory`, `PromptBuilder`, `ProposalValidator`, `MealPlanService`, `PlanChangeCommand` | `interpretModification()`, `proposeChange()`, `validateChange()`, `confirmChange()`, `executeChange()`, `undoLastChange()` | SD03 | Command, Facade, Adapter |
| F11 | Cooking-time-aware planning | Hybrid | UC01, UC04, UC08 | `UserProfile`, `TimeAwareStrategy`, `ConstraintValidator`, `UpdatePlanConstraintCommand` | `maxMinutesFor()`, `rank()`, `validateTime()` | SD01, SD03, SD05 | Strategy, Factory Method, Command |
| F12 | Budget-aware planning | Hybrid | UC01, UC04, UC06 | `BudgetStrategy`, `BudgetService`, `ConstraintValidator`, `GroceryService` | `rank()`, `estimateCost()`, `evaluate()`, `validatePlan()` | SD01, SD02 | Strategy, Factory Method |
| F13 | Dynamic plan adaptation | Hybrid | UC09 | `PlanRelevantChangePublisher`, `PantryService`, `ProfileService`, `MealPlanImpactObserver`, `GroceryListImpactObserver`, `MealPlanService`, `MealPlanningFacade`, `AgentController`, `MealPlanningAgent` | `publish()`, `onPlanRelevantChange()`, `assessImpact()`, `recordImpact()`, `markAffectedListStale()`, `adaptPlan()`, `proposeAdaptation()` | SD04 | Observer, Command, Facade |

All features are reached through `MealPlanningFacade`, which is why Facade appears in most rows. GUI requests pass through `MealPlanningApi`; CLI requests call the facade directly.

## Feature realization

### F01 - User goals and preferences

**Related use case:** UC01. **Related sequence diagram:** SD04 (notes the profile-change path).

**Classes involved**

- `MealPlanningGUI` / `MealPlanningCLI`: collect profile fields.
- `MealPlanningFacade`: exposes `updateProfile()` to both interfaces.
- `ProfileService`: validates the profile, saves it, and publishes change events.
- `UserProfile`: holds goals, restrictions, allergies, meals per day, budget, and time limits; `isAllowed()` and `maxMinutesFor()` answer constraint queries.
- `ProfileRepository`: stores profiles.

**Important methods:** `MealPlanningFacade.updateProfile()`, `ProfileService.updateProfile()`, `ProfileRepository.save()`, `ProfileService.publish()`.

**Execution.** The facade passes the edited profile to `ProfileService.updateProfile()`. The service validates ranges and contradictions within the profile, saves it, then publishes a `PlanRelevantChange` so that an affected plan can be adapted (F13). This feature is entirely deterministic; no LLM decides whether a profile is valid. Invalid values are rejected with field-level feedback.

### F02 - Pantry inventory management

**Related use case:** UC02. **Related sequence diagram:** SD04.

**Classes involved**

- `MealPlanningFacade`: exposes `updatePantry()`.
- `PantryService`: validates and applies item changes and publishes events.
- `Pantry` / `PantryItem`: inventory state; `availableQuantity()` excludes expired stock.
- `PantryRepository`: stores the pantry.

**Important methods:** `PantryService.updateItem()`, `Pantry.updateItem()`, `PantryRepository.save()`, `PantryService.publish()`, `Pantry.availableQuantity()`.

**Execution.** `PantryService.updateItem()` validates the quantity and unit, updates `Pantry`, saves it, and publishes `PANTRY_CHANGED`. All logic is deterministic; the agent is involved only if the change triggers adaptation (F13). Negative quantities and unknown units are rejected.

### F03 - Recipe search and recommendation

**Related use case:** UC03. **Related sequence diagrams:** SD05; SD01 reuses the retrieval step.

**Classes involved**

- `AgentController`: builds the `PlanningContext` and coordinates the request.
- `MealPlanningAgent`: lets the LLM choose the retrieval tool, applies the strategy through `rankRecipes()`, and has the LLM explain the result.
- `ToolManager`: dispatches the named tool.
- `RecipeTool`: retrieves candidates.
- `RecipeRepository`: provides curated recipes.
- `ExternalRecipeAdapter`: provides external recipes behind `RecipeDataSource`.
- `PlanningStrategyFactory` / `PlanningStrategy`: create and configure the deterministic ranking policy (`strategyFor()`, `rank()`).

**Important methods:** `AgentController.recommendRecipe()`, `MealPlanningAgent.recommendRecipes()`, `ToolManager.execute()`, `RecipeTool.searchRecipes()`, `RecipeRepository.search()`, `RecipeDataSource.find()`, `MealPlanningAgent.rankRecipes()`, `PlanningStrategy.rank()`.

**Execution.** The agent calls the recipe tool through `ToolManager`. The tool queries the repository first and the external adapter only when there are too few matches. The agent then ranks candidates with the strategy chosen for the planning mode. **AI:** tool selection and the explanation. **Deterministic:** retrieval, filtering, strategy ranking, and `ConstraintValidator.validateRestrictions()` on each result. If nothing fits, near matches are returned with their violated constraint, or generation (F04) is offered.

### F04 - AI recipe generation

**Related use case:** UC03 (alternative 5a). **Related sequence diagram:** SD05 (`alt` branch).

**Classes involved**

- `MealPlanningAgent`: decides when generation is needed.
- `PromptBuilder`: builds the recipe prompt from the context.
- `LLMClient` / `ProviderLLMAdapter`: call the external LLM API and return schema-constrained data.
- `ConstraintValidator`: checks allergens, restrictions, and time.
- `NutritionService`: calculates the recipe's nutrition.

**Important methods:** `MealPlanningAgent.generateRecipe()`, `PromptBuilder.buildRecipePrompt()`, `LLMClient.generateStructured()`, `ConstraintValidator.validateRestrictions()`, `NutritionService.calculateNutrition()`.

**Execution.** When no compatible candidate exists, or the user asks for a custom recipe, the agent builds a prompt and requests a recipe that matches a schema. The returned recipe (`source = GENERATED`) is validated and its nutrition calculated before it is shown. **AI:** recipe creation. **Deterministic:** schema, restriction, and nutrition checks. Malformed output is retried once and then declined; a recipe that breaks a restriction is never shown.

### F05 - Weekly meal-plan generation

**Related use case:** UC04. **Related sequence diagram:** SD01.

**Classes involved**

- `MealPlanningApi` / `MealPlanningFacade`: receive the `PlanRequest`.
- `AgentController`: builds context, runs validation, and decides between saving and reporting a conflict.
- `MealPlanningAgent`: plans in several steps; runs the LLM-driven tool loop, then requests the final proposal.
- `PlanningStrategyFactory`: creates the strategy for the requested `PlanningMode`.
- `ToolManager`: lists the tools, validates each LLM-chosen call (`isValidCall()`), and executes it.
- `RecipeTool`: retrieves candidates.
- `PromptBuilder` / `LLMClient`: build the tool-selection and planning prompts and return the next action or the structured `PlanningProposal`.
- `ProposalValidator`: checks structure, recipe IDs, and slot count.
- `ConstraintValidator`: checks restrictions, time, nutrition, and cost.
- `MealPlanService` / `MealPlanRepository`: create and save the `MealPlan`.

**Important methods:** `AgentController.generateWeeklyPlan()`, `MealPlanningAgent.createPlanProposal()`, `PlanningStrategyFactory.strategyFor()`, `PromptBuilder.buildToolSelectionPrompt()`, `ToolManager.isValidCall()`, `ToolManager.execute()`, `PlanningStrategy.rank()`, `LLMClient.generateStructured()`, `ProposalValidator.validate()`, `ConstraintValidator.validatePlan()`, `MealPlanService.createFromProposal()`.

**Execution.** The controller asks the agent for a proposal. The agent obtains a strategy and runs its tool loop: the LLM decides which tool to call next, `ToolManager` rejects invalid calls (returning the error to the LLM) and executes valid ones, and recipe results are ranked by the strategy before being fed back. When the LLM signals it has enough information, the agent asks it for a structured weekly proposal. The controller validates it. If it fails, the violations are added to the context and the agent tries once more. A valid proposal is saved; otherwise a `CONFLICT` response with trade-offs is returned and nothing is saved. **AI:** tool selection and arguments, choosing among strategy-ranked candidates when composing the week, and the rationale. **Deterministic:** strategy ranking, every check, and the save.

### F06 - Nutrition analysis

**Related use case:** UC05. **Related sequence diagrams:** SD01 (plan validation), SD05 (per recipe).

**Classes involved**

- `NutritionService`: totals per-ingredient values (`Ingredient.nutritionPer100g`) from the ingredient catalogue, scaled by quantity in grams (D09).
- `NutritionSummary`: result with a `complete` flag.
- `NutritionTool`: exposes the calculation to the agent.
- `ConstraintValidator`: uses it during plan validation.

**Important methods:** `NutritionService.calculateNutrition()`, `NutritionService.dailyTotals()`, `NutritionTool.analyze()`.

**Execution.** The facade calls the service directly for the nutrition panel; the agent and validators use the same service, so there is one implementation of the arithmetic. This feature is entirely deterministic. Missing data produces an incomplete, clearly labelled total.

### F07 - Grocery-list generation

**Related use case:** UC06. **Related sequence diagram:** SD02.

**Classes involved**

- `GroceryService`: calculates shortages.
- `Pantry`: provides usable quantities.
- `GroceryList` / `GroceryItem`: hold required, in-pantry, and to-buy quantities.
- `GroceryTool`: exposes the projection to the agent.

**Important methods:** `GroceryService.generateList()`, `Pantry.availableQuantity()`, `Quantity.subtract()`, `GroceryTool.project()`.

**Execution.** For each `IngredientRequirement` of each planned meal, the service asks the pantry for the usable quantity and records the shortfall. This feature is entirely deterministic. Incompatible units are flagged instead of converted unsafely.

### F08 - Grocery-list optimization

**Related use cases:** UC06; UC07 extends it. **Related sequence diagram:** SD02.

**Classes involved**

- `GroceryService`: combines duplicates and attaches prices.
- `PriceDataSource` / `ExternalPriceAdapter`: provide indicative prices.
- `BudgetService`: produces `BudgetStatus` with the estimate, shortfall, and cost drivers.
- `AgentController`: starts conflict resolution.
- `MealPlanningAgent` / `SubstitutionTool`: propose cheaper alternatives.
- `ConstraintValidator`: recalculates the effect of the proposed change.

**Important methods:** `GroceryService.optimizeList()`, `GroceryService.consolidate()`, `PriceDataSource.estimatePrice()`, `BudgetService.evaluate()`, `AgentController.resolveBudgetConflict()`, `MealPlanningAgent.proposeSubstitution()`, `ConstraintValidator.validateChange()`.

**Execution.** Combining items, pricing, and budget evaluation are deterministic. Only when the list is over budget does the agent propose substitutions for the cost drivers, and those are recalculated before being offered. **AI:** choosing alternatives. **Deterministic:** all money arithmetic. A missing price gives a partial estimate, and an unavoidable shortfall is reported with its exact amount.

### F09 - Ingredient substitution

**Related use case:** UC07. **Related sequence diagrams:** SD02 (budget-driven), SD03 (confirmation and command execution).

**Classes involved**

- `AgentController`: entry point (`requestSubstitution()` or `resolveBudgetConflict()`).
- `MealPlanningAgent`: selects and explains a substitute.
- `SubstitutionTool` / `IngredientRepository`: provide catalogue-compatible candidates.
- `ConstraintValidator`: enforces allergies, restrictions, time, and budget.
- `SubstituteIngredientCommand`: applies the change and can undo it.

**Important methods:** `AgentController.requestSubstitution()`, `MealPlanningAgent.proposeSubstitution()`, `SubstitutionTool.findCandidates()`, `IngredientRepository.findSubstitutes()`, `ConstraintValidator.validateChange()`, `SubstituteIngredientCommand.execute()` / `undo()`.

**Execution.** Candidates come from the deterministic catalogue, the agent chooses among them, and validation runs before the preview. The plan changes only when the user confirms, through the command. If no valid substitute exists, nothing changes and the user gets options.

### F10 - Natural-language meal-plan modification

**Related use case:** UC08. **Related sequence diagram:** SD03.

**Classes involved**

- `AgentController`: interprets the request and confirms changes.
- `MealPlanningAgent`: turns the instruction into a structured `PlanChange`.
- `ConversationMemory`: recalls and records confirmed decisions.
- `PromptBuilder` / `LLMClient`: produce the change.
- `ProposalValidator` / `ConstraintValidator`: validate the target and constraints.
- `MealPlanService`: invoker that creates commands, executes them, and keeps the undo history.
- `PlanChangeCommand` (for example `ReplaceMealCommand`): applies the change to `MealPlan`.
- `GroceryService`: refreshes the list.

**Important methods:** `AgentController.interpretModification()`, `MealPlanningAgent.proposeChange()`, `ConversationMemory.retrieveRelevantContext()`, `LLMClient.generateStructured()`, `ProposalValidator.validate()`, `ConstraintValidator.validateChange()`, `AgentController.confirmChange()`, `MealPlanService.executeChange()`, `PlanChangeCommand.execute()`, `GroceryService.refreshForPlan()`, `MealPlanService.undoLastChange()`.

**Execution.** The agent never edits the plan directly; it returns a `PlanChange`. After validation and a preview, the user confirms. The controller then has `MealPlanService` create and execute the matching command, records the decision in memory, and refreshes the grocery list. **AI:** interpreting the instruction. **Deterministic:** validation, the change itself, and undo. An ambiguous request produces a clarification question; a provider failure after one retry produces `FAILED` with the plan unchanged.

### F11 - Cooking-time-aware planning

**Related use cases:** UC01 (limits), UC04 and UC08 (application). **Related sequence diagrams:** SD01, SD03, SD05.

**Classes involved**

- `UserProfile`: stores per-day limits; `maxMinutesFor()`.
- `TimeAwareStrategy`: ranks faster recipes higher.
- `ConstraintValidator`: rejects any meal over its day's limit.
- `UpdatePlanConstraintCommand`: applies a changed limit and the meal replacements it requires.

**Important methods:** `UserProfile.maxMinutesFor()`, `TimeAwareStrategy.rank()`, `ConstraintValidator.validateTime()`, `Recipe.totalMinutes()`.

**Execution.** Strategies only express preference; `validateTime()` is the deterministic gate inside `validatePlan()` and `validateChange()`. When the limit changes, adaptation (F13) replaces only the affected meals. If nothing fits, the conflict and the fastest options are reported.

### F12 - Budget-aware planning

**Related use cases:** UC01 (budget), UC04, UC06. **Related sequence diagrams:** SD01 (cost estimate), SD02 (final budget status).

**Classes involved**

- `BudgetStrategy`: ranks cheaper, pantry-reusing recipes.
- `BudgetService`: `estimateCost()` during planning and `evaluate()` for grocery lists.
- `ConstraintValidator`: applies the estimate.
- `GroceryService`: provides the priced list.

**Important methods:** `BudgetStrategy.rank()`, `BudgetService.estimateCost()`, `BudgetService.evaluate()`, `ConstraintValidator.validatePlan()`.

**Execution.** The budget affects ranking, plan validation, and grocery evaluation, and an over-budget result leads to F08 alternatives. Every amount is calculated deterministically; the agent only proposes trade-offs. An impossible budget reports the exact shortfall.

### F13 - Dynamic plan adaptation

**Related use case:** UC09 (extends UC01 and UC02). **Related sequence diagram:** SD04.

**Classes involved**

- `PantryService` / `ProfileService`: concrete `PlanRelevantChangePublisher`s.
- `GroceryListImpactObserver`: marks the related list stale.
- `MealPlanImpactObserver`: assesses impact and records it; it makes no LLM call and does not depend on `AgentController`.
- `MealPlanService`: `assessImpact()` returns a `PlanImpact`; `recordImpact()` / `getPendingImpact()` keep it until the user acts.
- `MealPlanningFacade`: `adaptPlan()` starts adaptation when the user asks.
- `AgentController` / `MealPlanningAgent`: propose a minimal `PlanChange` through the tool loop.
- `ConstraintValidator`: validates it.
- Commands: apply it after confirmation.

**Important methods:** `PlanRelevantChangePublisher.publish()`, `PlanImpactObserver.onPlanRelevantChange()`, `GroceryService.markAffectedListStale()`, `MealPlanService.assessImpact()`, `MealPlanService.recordImpact()`, `MealPlanningFacade.adaptPlan()`, `MealPlanService.getPendingImpact()`, `AgentController.adaptPlan()`, `MealPlanningAgent.proposeAdaptation()`, `AgentController.confirmChange()`.

**Execution.** A saved change is published once. Each observer reacts independently, so the services that publish need no knowledge of planning or groceries. Observers do only fast, deterministic work, so a pantry update never waits for the LLM. The adaptation is proposed when the user chooses it. Only affected meals are re-planned, and the change waits for confirmation. **AI:** the proposed adaptation. **Deterministic:** detecting impact, marking the list stale, validation, and applying the change. If no feasible adaptation exists, the current plan is kept and marked unresolved.
