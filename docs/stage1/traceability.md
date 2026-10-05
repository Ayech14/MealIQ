# Feature-to-design traceability and realization

## Traceability table

| Feature | Type | Use case | Classes | Key methods | Sequence | Patterns |
|---|---|---|---|---|---|---|
| F01 Preferences | Deterministic | UC01 | `ProfileService`, `UserProfile`, `ProfileRepository`, `MealPlanningFacade` | `updateProfile()`, `save()` | SD04 (when changed) | Facade, Observer |
| F02 Pantry | Deterministic | UC02 | `PantryService`, `Pantry`, `PantryItem`, `PantryRepository` | `updateItem()`, `availableQuantity()` | SD04 | Facade, Observer |
| F03 Search/recommend | Hybrid | UC03 | `AgentController`, `MealPlanningAgent`, `ToolManager`, `RecipeTool`, `RecipeRepository` | `recommendRecipe()`, `searchRecipes()`, `rankRecipes()` | SD01 | Strategy, Facade, Adapter |
| F04 Generate recipe | Hybrid | UC03 | `MealPlanningAgent`, `PromptBuilder`, `LLMClient`, `ProviderLLMAdapter`, `ProposalValidator`, `NutritionService` | `generateStructured()`, `validate()`, `calculateNutrition()` | SD01 | Adapter, Facade |
| F05 Weekly plan | Hybrid | UC04 | `AgentController`, `MealPlanningAgent`, `PlanningStrategyFactory`, `ConstraintValidator`, `MealPlanService` | `generateWeeklyPlan()`, `createPlanProposal()`, `createFromProposal()` | SD01 | Strategy, Factory Method, Facade |
| F06 Nutrition | Deterministic | UC05 | `NutritionService`, `NutritionTool`, `Recipe`, `MealPlan` | `calculateNutrition()`, `analyze()` | SD01 | Facade |
| F07 Grocery generation | Deterministic | UC06 | `GroceryService`, `GroceryTool`, `Pantry`, `GroceryList` | `generateList()`, `project()` | SD02 | Facade |
| F08 Grocery optimization | Hybrid | UC06, UC07 | `GroceryService`, `BudgetService`, `MealPlanningAgent`, `SubstitutionTool` | `optimizeList()`, `evaluate()`, `proposeAdaptation()` | SD02 | Strategy, Facade, Adapter |
| F09 Substitution | Hybrid | UC07 | `SubstitutionTool`, `MealPlanningAgent`, `ConstraintValidator`, `SubstituteIngredientCommand` | `findCandidates()`, `validatePlan()`, `execute()` | SD02, SD03 | Command, Adapter |
| F10 NL modification | Hybrid | UC08 | `AgentController`, `MealPlanningAgent`, `PlanChangeCommand`, `MealPlanService` | `interpretModification()`, `proposeChange()`, `executeChange()` | SD03 | Command, Facade, Adapter |
| F11 Time-aware planning | Hybrid | UC01, UC04 | `TimeAwareStrategy`, `ConstraintValidator`, `MealPlanningAgent` | `rank()`, `validateTime()` | SD01, SD03 | Strategy, Factory Method |
| F12 Budget-aware planning | Hybrid | UC01, UC04, UC06 | `BudgetStrategy`, `BudgetService`, `GroceryService`, `ConstraintValidator` | `rank()`, `evaluate()`, `validatePlan()` | SD01, SD02 | Strategy, Factory Method |
| F13 Dynamic adaptation | Hybrid | UC09 | `PlanRelevantChangePublisher`, `MealPlanImpactObserver`, `GroceryListImpactObserver`, `MealPlanService`, `MealPlanningAgent` | `publish()`, `onPlanRelevantChange()`, `assessImpact()`, `adaptPlan()` | SD04 | Observer, Command, Facade |

## Feature realization

### F01 - User goals and preferences

UC01 persists `UserProfile` through `ProfileService.updateProfile()` and `ProfileRepository`; the facade exposes it equally to GUI/CLI. This is deterministic validation and persistence. A successful change publishes an event so an active plan can be reassessed (SD04); no LLM decides profile validity.

### F02 - Pantry inventory management

UC02 uses `PantryService` to validate and persist `PantryItem` changes within `Pantry`. `availableQuantity()` excludes unusable stock, while the service publishes `PlanRelevantChange` for SD04. Quantity/unit/expiry rules are deterministic; the agent is only involved if the user later asks to adapt the plan.

### F03 - Recipe search and recommendation

In UC03, `AgentController` gives the agent profile/pantry context; `RecipeTool.searchRecipes()` retrieves candidates through repository/data-source abstractions. The agent ranks and explains candidates using a selected strategy, while `ConstraintValidator` guards hard restrictions. SD01 shows the same retrieval/planning path; an empty result returns disclosed trade-offs rather than a fabricated recipe.

### F04 - AI recipe generation

When UC03 has no suitable retrieved result or the user requests one, `PromptBuilder` and `LLMClient.generateStructured()` obtain a structured candidate through `ProviderLLMAdapter` and the planned external LLM API. `ProposalValidator` checks schema and `NutritionService` computes/labels nutrition; restrictions are deterministically checked before display. SD01 covers this agent/tool collaboration; malformed output is retried once or declined.

### F05 - Weekly meal-plan generation

UC04 invokes `AgentController.generateWeeklyPlan()`, which collects profile/pantry facts and asks `MealPlanningAgent.createPlanProposal()`. The strategy factory supplies a ranking policy; tools supply candidates/facts; `ConstraintValidator` and `MealPlanService.createFromProposal()` make the final persisted result deterministic. SD01 returns an explicit conflict report when constraints cannot coexist.

### F06 - Nutrition analysis

UC05 calls `NutritionService.calculateNutrition()` directly through the facade (or indirectly through `NutritionTool` in planning). It totals stored nutrition values for recipes/meals/plans, so no LLM calculates macros. SD01 shows plan-time use; missing ingredient data is preserved as incomplete instead of guessed.

### F07 - Grocery-list generation

UC06 sends the selected `MealPlan` and `Pantry` to `GroceryService.generateList()`, exposed through `GroceryTool` for agent workflows. The service subtracts usable quantities deterministically and creates `GroceryList`; SD02 shows the interaction. Unconvertible units stop the affected calculation and request a resolution.

### F08 - Grocery-list optimization

`GroceryService.optimizeList()` deterministically consolidates duplicate ingredient needs and `BudgetService.evaluate()` calculates the estimate in UC06. If over budget, the agent can use `SubstitutionTool` to propose alternatives, but must not claim the list is compliant until deterministic re-evaluation (SD02). Missing price data produces a partial estimate/warning.

### F09 - Ingredient substitution

In UC07, `SubstitutionTool.findCandidates()` supplies catalog-compatible options and the agent ranks them against context. `ConstraintValidator` checks allergy/restriction/time/budget effects; `SubstituteIngredientCommand.execute()` changes the plan only after confirmation. SD02/SD03 demonstrate budget-driven and user-requested substitutions; no valid substitute means no mutation.

### F10 - Natural-language meal-plan modification

UC08 gives the agent the instruction and active plan; `proposeChange()` returns structured `PlanChange`, not direct state mutation. A validator resolves the target/constraints, then `MealPlanService.executeChange()` invokes a command and refreshes grocery information (SD03). Ambiguity prompts clarification; the original plan survives rejection/cancel.

### F11 - Cooking-time-aware planning

UC01 captures per-day limits, UC04/UC08 apply them. `TimeAwareStrategy.rank()` prefers fast options, but `ConstraintValidator.validateTime()` deterministically rejects a meal beyond its slot limit in SD01/SD03. If none fit, the agent explains the conflict and offers fast alternatives.

### F12 - Budget-aware planning

UC01 stores the budget; UC04/UC06 use `BudgetStrategy` to rank and `BudgetService.evaluate()` to calculate and enforce the estimate. SD01 projects needs and SD02 makes the final grocery estimate visible; an agent-suggested change is revalidated. An unavoidable shortfall is reported with trade-offs, never hidden.

### F13 - Dynamic plan adaptation

UC09 begins when `PantryService` or `ProfileService` publishes a relevant change. `MealPlanImpactObserver.onPlanRelevantChange()` requests `MealPlanService.assessImpact()`, while `GroceryListImpactObserver` marks the related list stale; the agent proposes minimal `PlanChange` alternatives and commands apply confirmed changes (SD04). Failure/infeasibility retains the old plan and labels it unresolved.
