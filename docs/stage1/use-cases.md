# Use cases

The primary actor is the **Meal Planner**. The planned external LLM API and recipe/price provider are supporting actors; they provide services to MealIQ but do not initiate user use cases. GUI and CLI requests reach the same `MealPlanningFacade` use cases through planned FastAPI delivery adapters.

## UC01 - Configure profile and constraints

**Actor:** Meal Planner. **Goal:** save planning goals, dietary needs, preferences, meals/day, budget, and time limits. **Preconditions:** system is available. **Trigger:** user saves the Profile/Constraints screen or equivalent CLI command.
**Main success scenario:** 1) UI collects fields. 2) Facade calls `ProfileService.updateProfile()`. 3) service validates values and resolves/reports internal profile contradictions. 4) repository saves `UserProfile`. 5) publisher emits a plan-relevant change. 6) UI shows saved profile and any affected-plan notice.
**Alternatives:** invalid value is rejected with field feedback; a new hard restriction conflicts with the active plan, so adaptation is offered. **Postconditions:** validated profile is persisted; dependent plan can be marked affected. **Features:** F01, F11, F12, F13.

## UC02 - Manage pantry

**Actor:** Meal Planner. **Goal:** maintain available ingredients. **Preconditions:** profile exists. **Trigger:** add/edit/remove/inspect pantry entry.
**Main success scenario:** 1) UI submits `PantryItem` data. 2) facade invokes `PantryService.updateItem()`. 3) service validates quantity/unit and updates `Pantry`. 4) repository saves it. 5) service publishes `PlanRelevantChange`. 6) UI shows inventory and impact notice.
**Alternatives:** negative or incompatible quantity is rejected; expired entries remain visible but are excluded from available stock. **Postconditions:** pantry is current and observers are notified. **Features:** F02, F13.

## UC03 - Find or generate a recipe

**Actor:** Meal Planner; supporting actors: recipe provider, external LLM API. **Goal:** receive a compatible recipe recommendation or custom recipe. **Preconditions:** profile is available; recipe data source and LLM adapter are configured when applicable. **Trigger:** recipe search or generate request.
**Main success scenario:** 1) UI submits criteria. 2) `AgentController.recommendRecipe()` gets profile/pantry context. 3) `RecipeTool.searchRecipes()` retrieves candidates. 4) agent ranks candidates using strategy and returns a recommendation. 5) if requested/no suitable candidate, `PromptBuilder` and `LLMClient.generateStructured()` obtain a schema-constrained proposal through `ProviderLLMAdapter` and the external LLM API. 6) validator and nutrition service validate the result. 7) UI displays recipe/rationale.
**Alternatives:** no compatible recipe returns trade-offs; malformed or invalid generated output is rejected; provider failure returns a retry/clear error. **Postconditions:** compatible result is displayed and may be selected. **Features:** F03, F04, F06, F09, F11, F12.

## UC04 - Generate weekly meal plan

**Actor:** Meal Planner; supporting actors: recipe provider, external LLM API. **Goal:** create a multi-day, multi-meal plan. **Preconditions:** profile and planning period supplied. **Trigger:** Generate Plan action.
**Main success scenario:** 1) facade calls `AgentController.generateWeeklyPlan()`. 2) controller loads profile, pantry, recipes, and prior context. 3) agent selects strategy and tools. 4) it constructs a proposal. 5) deterministic nutrition, time, restriction, and preliminary cost checks run. 6) `MealPlanService.createFromProposal()` persists the accepted plan. 7) UI displays calendar, rationale, and warnings.
**Alternatives:** agent requests missing critical information; infeasible set returns prioritized alternatives; tool failure preserves no partial plan. **Postconditions:** a validated plan or an explicit conflict report exists. **Features:** F01, F03-F06, F11, F12.

## UC05 - Analyze nutrition

**Actor:** Meal Planner. **Goal:** inspect nutrition of a recipe, meal, or plan. **Preconditions:** target recipe/plan exists. **Trigger:** Nutrition panel request.
**Main success scenario:** facade calls `NutritionService.calculateNutrition()` and presents totals plus completeness status. **Alternatives:** missing nutrient data produces a partial/labeled result. **Postconditions:** no domain state is changed. **Features:** F06.

## UC06 - Generate and optimize grocery list

**Actor:** Meal Planner; supporting actor: price provider. **Goal:** obtain a de-duplicated, pantry-aware list and budget estimate. **Preconditions:** selected plan and pantry exist. **Trigger:** Generate/Optimize Grocery List action.
**Main success scenario:** 1) facade loads plan/pantry. 2) `GroceryService.generateList()` subtracts usable stock. 3) `optimizeList()` consolidates quantities and consults prices. 4) `BudgetService.evaluate()` returns estimate/status. 5) UI displays list/warnings.
**Alternatives:** unknown price gives a partial estimate; incompatible units request resolution; over-budget status can invoke UC07. **Postconditions:** list is saved/displayed with estimate and warnings. **Features:** F02, F07, F08, F12.

## UC07 - Substitute ingredient or resolve budget conflict

**Actor:** Meal Planner; supporting actor: external LLM API. **Goal:** select a safe compatible alternative. **Preconditions:** selected recipe/plan and a target ingredient or budget conflict exist. **Trigger:** Substitute action or optimization conflict.
**Main success scenario:** agent calls `SubstitutionTool.findCandidates()`, ranks candidates, then validators check restriction, nutrition, time, and budget effects. UI previews selection; on confirmation a `SubstituteIngredientCommand` updates the plan. **Alternatives:** no valid candidate reports options without mutation; user cancels. **Postconditions:** confirmed plan/list reflects substitution, or original remains intact. **Features:** F08, F09, F11, F12.

## UC08 - Modify meal plan in natural language

**Actor:** Meal Planner; supporting actor: external LLM API. **Goal:** change a targeted meal/constraint through ordinary language. **Preconditions:** active plan exists. **Trigger:** text instruction.
**Main success scenario:** 1) controller sends context to agent. 2) agent returns structured `PlanChange`. 3) validator checks target and constraints. 4) UI previews it. 5) on confirmation `MealPlanService.executeChange()` applies a command. 6) grocery list/nutrition are refreshed and plan saved. **Alternatives:** ambiguous instruction requests clarification; invalid/infeasible change returns choices; cancel/no confirmation causes no mutation. **Postconditions:** auditably changed plan and affected grocery list, or unchanged plan. **Features:** F06-F12.

## UC09 - Adapt affected plan

**Actor:** Meal Planner; supporting actor: external LLM API. **Goal:** revise only the portions affected by inventory, preference, time, or budget change. **Preconditions:** active plan exists and a plan-relevant change occurred. **Trigger:** event notice or explicit “adapt” request.
**Main success scenario:** 1) a publisher emits `PlanRelevantChange`. 2) `MealPlanImpactObserver` identifies affected meals through `MealPlanService`; `GroceryListImpactObserver` marks the grocery list stale. 3) controller asks the agent for a minimal adaptation proposal. 4) validators evaluate it. 5) UI previews the impact. 6) confirmed commands update the plan and refresh its grocery list. **Alternatives:** no feasible adaptation reports impact and options; failure leaves old plan intact. **Postconditions:** affected artifacts are updated after confirmation or marked unresolved. **Features:** F02, F09-F13.
