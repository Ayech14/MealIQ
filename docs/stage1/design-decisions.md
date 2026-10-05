# Important design decisions and future testability

## Decision record

### D01 - Hybrid agent with deterministic guardrails

The external LLM API supports interpretation, tool selection, candidate ranking, recipe/substitution proposals, and multi-step planning. Deterministic services calculate nutrition, costs, quantities, and grocery consolidation; validate inputs and hard constraints; and own persistence. This prevents generated text from becoming an authority for arithmetic or durable state.

### D02 - Shared facade and API boundary

The React GUI and Python Typer CLI use the same FastAPI/application boundary and `MealPlanningFacade`. Interface clients collect input and present results, while use-case coordination remains in services and the agent layer. This avoids duplicated business rules and keeps CLI/GUI outcomes consistent.

### D03 - Structured proposals before state mutation

`MealPlanningAgent` returns `PlanningProposal` or `PlanChange` DTOs, not direct mutations. Pydantic models are planned for transport/schema validation; `ProposalValidator`, `ConstraintValidator`, nutrition, grocery, and budget services then validate domain feasibility. A user-visible preview precedes command execution. Invalid or unconfirmed proposals leave the active plan unchanged.

### D04 - Constraint precedence and conflict transparency

Allergies and dietary restrictions are hard constraints. Meals-per-day structure, explicit cooking-time limits, and explicit budget limits are validated planning constraints. Preferred/disliked foods and optimization preferences are soft constraints. If no compliant solution exists, MealIQ returns an explicit conflict and alternatives rather than silently relaxing a constraint.

### D05 - Provider-neutral external integrations

The Stage 2 plan integrates an external LLM API, initially through an OpenAI-oriented `ProviderLLMAdapter`; the precise provider/model remains configurable. `LLMClient`, `RecipeDataSource`, and provider adapters prevent external API/SDK types from entering domain code. This supports portability, controlled fakes, failure handling, and provider replacement without an architecture rewrite.

### D06 - Event-driven impact detection within the application boundary

`PantryService` and `ProfileService` publish `PlanRelevantChange` after a successful update. `MealPlanImpactObserver` identifies affected meals and requests an adaptation proposal; `GroceryListImpactObserver` marks related lists stale. This local Observer mechanism is sufficient for the planned modular application and does not require a message broker.

### D07 - Test seams are first-class design elements

Repository interfaces, injected provider adapters, typed tool results, strategy interfaces, commands, and event observers allow isolated tests with controlled state. Stage 3 can test deterministic services with pytest and evaluate LLM behavior separately with KUMA-style behavioral tests.

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
