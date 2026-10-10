# Important design decisions and future testability

## Decision record

### D01 - Hybrid agent with deterministic guardrails

The external LLM API supports interpretation, tool selection, choosing among ranked candidates, recipe and substitution proposals, and multi-step planning. Candidate scoring itself is deterministic: the selected `PlanningStrategy` ranks candidates, and the LLM chooses among the top-ranked options and explains its choices. Deterministic services calculate nutrition, costs, quantities, and grocery consolidation; validate inputs and hard constraints; and own persistence. This prevents generated text from becoming an authority for arithmetic or durable state.

### D02 - Shared facade and API boundary

The React GUI and the Python Typer CLI share one application boundary, `MealPlanningFacade`. The GUI runs in a browser, so it calls `MealPlanningApi`, a thin FastAPI route layer that forwards each request to the facade. The CLI is written in Python and calls the facade in-process. Interface clients only collect input and present results; use-case coordination stays in the controller, the services, and the agent. This avoids duplicated business rules and keeps GUI and CLI outcomes identical. A considered alternative, routing the CLI through HTTP as well, was rejected because it would require a running server for local scripted use without adding any business value.

### D03 - Structured proposals before state mutation

`MealPlanningAgent` returns `PlanningProposal` or `PlanChange` DTOs, not direct mutations. Pydantic models are planned for transport/schema validation; `ProposalValidator`, `ConstraintValidator`, nutrition, grocery, and budget services then validate domain feasibility. A user-visible preview precedes command execution. Invalid or unconfirmed proposals leave the active plan unchanged.

### D04 - Constraint precedence and conflict transparency

Allergies and dietary restrictions are hard constraints. Meals-per-day structure, explicit cooking-time limits, and explicit budget limits are validated planning constraints. Preferred/disliked foods and optimization preferences are soft constraints. If no compliant solution exists, MealIQ returns an explicit conflict and alternatives rather than silently relaxing a constraint.

### D05 - Provider-neutral external integrations

The Stage 2 plan integrates an external LLM API through an OpenAI-oriented `ProviderLLMAdapter`, using an OpenAI GPT model with structured-output support; the model version is pinned in configuration and can be changed without code changes. `LLMClient`, `RecipeDataSource`, and provider adapters prevent external API/SDK types from entering domain code. This supports portability, controlled fakes, failure handling, and provider replacement without an architecture rewrite.

### D06 - Event-driven impact detection within the application boundary

`PantryService` and `ProfileService` publish `PlanRelevantChange` after a successful update. `MealPlanImpactObserver` identifies affected meals and records the `PlanImpact` through `MealPlanService.recordImpact()`; `GroceryListImpactObserver` marks related lists stale. Observers do only fast, deterministic work: the LLM adaptation proposal is produced later, when the user chooses to adapt (`MealPlanningFacade.adaptPlan()`). This keeps pantry and profile updates fast and independent of LLM availability. It also avoids a dependency cycle (`AgentController` → `PantryService` → observer → `AgentController`) that would arise if an observer called the controller. This local Observer mechanism is sufficient for the planned modular application and does not require a message broker.

### D07 - LLM-driven tool selection with deterministic tools

The agent does not call tools in a fixed order. In a bounded loop, the LLM chooses the next tool and its arguments from those `ToolManager` reports. `ToolManager.isValidCall()` rejects unknown tools or invalid arguments before execution, and the error is returned to the LLM as an observation. The tools themselves are deterministic. A fixed pipeline was considered and rejected: it would be simpler, but the LLM would then only generate text, and tool selection, argument quality, and recovery from tool errors could not be designed or tested as agent behavior. The step limit (`maxToolSteps`) bounds cost and prevents endless loops.

### D08 - Test seams are first-class design elements

Repository interfaces, injected provider adapters, typed tool results, strategy interfaces, commands, and event observers allow isolated tests with controlled state. Stage 3 can test deterministic services with pytest and evaluate LLM behavior separately with KUMA behavioral tests.

### D09 - Nutrition data provenance and ingredient matching

**Source.** Nutrition values are stored per ingredient, not per recipe. Each `Ingredient` in the ingredient catalogue (`IngredientRepository`) carries `nutritionPer100g` (calories, protein, carbohydrates, and fat). Recipes reference catalogue ingredients through `IngredientRequirement`. `NutritionService` derives meal and plan totals from these values, so there is one source of truth and no LLM arithmetic.

**Open decision.** Which dataset seeds the catalogue is still undecided and will be chosen in Stage 2. The leading candidate is a public food-composition database (for example USDA FoodData Central), imported into the catalogue, rather than live API calls during planning. No integration with any nutrition provider exists yet.

**Matching rules.**

- Curated recipes reference catalogue ingredients by ID, so they need no matching.
- External recipes are mapped by `ExternalRecipeAdapter.toRecipe()`, which matches each ingredient name through `IngredientRepository.findByName()`. If any ingredient cannot be matched, the recipe is excluded from results: its allergens, nutrition, and cost cannot be verified.
- Generated recipes must use catalogue ingredient names supplied in the prompt. An unmatched ingredient fails validation and triggers the single retry described in F04.

**Incomplete data.**

- A matched ingredient whose catalogue entry lacks nutrition values contributes nothing to the totals, and the resulting `NutritionSummary` has `complete = false`.
- A quantity that cannot be converted to grams (for example "1 bunch", with no conversion defined) has the same effect.
- The interface shows such totals as incomplete. Values are never estimated by the LLM.

## Stage 3 testability

| Area | Deterministic pytest focus | Agent/KUMA behavioral focus |
|---|---|---|
| Nutrition and costs | `NutritionService`/`BudgetService`: totals, servings, partial data, boundaries | Requests the appropriate facts and uses returned values. |
| Pantry and grocery | Pantry updates, expiry exclusion, unit mismatch, shortage calculation, duplicate consolidation | Identifies list/plan effects after inventory changes. |
| Rules | Allergy/restriction, time, budget, invalid-input, and conflict validation | Follows constraints and explains unavoidable trade-offs. |
| Persistence and adapters | Repository contracts, SQLAlchemy mappings, adapter translation, provider error mapping with fakes | Selects tools, supplies valid arguments, and recovers from failed tools/providers. |
| Plan changes | Command execute/undo, no mutation on invalid change, observer notification | Clarifies ambiguous modifications and completes multi-step plan updates. |

Candidate behavioral requirements include correct recipe/nutrition/grocery/substitution tool selection, use of tool results rather than fabricated values, protection of hard restrictions, clarification of an ambiguous target meal, provider/tool failure recovery, completion of a plan-plus-grocery workflow, and explicit budget/time conflict reporting. Representative cases include conflicting preferences, unavailable ingredients, malformed structured output, repeated changes, and long plan context.

## Stage boundary

Stage 1 contains the design package only. The planned Stage 2 work encompasses the FastAPI backend, React interface, Typer CLI, SQLAlchemy persistence, PostgreSQL database, and external LLM API integration. Stage 3 covers deterministic automated testing and behavioral validation of the LLM-driven agent.
