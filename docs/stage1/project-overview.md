# MealIQ: project overview and architecture

## Problem, motivation, and target users

Meal planning is difficult when nutritional goals, dietary restrictions, allergies, disliked foods, pantry stock, spending limits, and uneven cooking time apply simultaneously. A recipe search can suggest individual dishes, but it does not reconcile a week of competing constraints, calculate shortages, consolidate purchases, or explain the consequences of a late change.

MealIQ is designed for an individual household meal planner who wants assistance organizing meals and groceries. It is a planning aid, not a medical, allergy-treatment, or clinical nutrition service. The system is appropriate for an agent because ordinary-language requests often imply a sequence of decisions: interpret the request, retrieve relevant recipes and facts, compare options under constraints, calculate concrete consequences, and propose an understandable next action.

## Agent description

`MealPlanningAgent` receives a normalized request and a bounded planning context containing the profile, pantry, active plan, and relevant prior confirmed decisions. It coordinates multi-step work through `ToolManager`, selects a planning strategy, and returns a structured `PlanningProposal` or `PlanChange` containing proposed meals, substitutions, rationale, and conflicts. It does not directly persist or mutate a `MealPlan`.

`ConversationMemory` stores only relevant prior requests and confirmed decisions for retrieval. Durable facts such as profiles, pantry items, recipes, meal plans, and grocery lists remain in repositories. This distinction prevents transient conversational context from becoming the source of truth for domain state.

## AI/LLM integration plan

MealIQ is designed to integrate an **external LLM API in Stage 2**. OpenAI is the initial planned provider; the exact model is intentionally deferred until implementation, where it can be selected according to capability, cost, availability, and structured-output support. The architecture remains compatible with additional provider adapters, including Anthropic or Gemini.

The agent invokes the provider-neutral `LLMClient` interface. At runtime, `ProviderLLMAdapter` translates `PromptBuilder` output and structured response schemas to the selected provider API/SDK, then maps the returned data into internal DTOs. The intended flow is:

```text
MealPlanningAgent -> PromptBuilder -> LLMClient
                                       |
                              ProviderLLMAdapter -> External LLM API
                                       |
Structured response -> PlanningProposal / PlanChange -> validation and command pipeline
```

The LLM is used for meaningful agent behavior: interpreting natural-language modifications, deciding which available tools are relevant, reasoning about soft-constraint trade-offs, proposing recipe/substitution alternatives, and creating a plan proposal when retrieval alone is insufficient. It is not trusted to calculate nutrition, cost, pantry quantities, or validate hard restrictions. `ProposalValidator`, `ConstraintValidator`, `NutritionService`, `GroceryService`, and `BudgetService` validate the proposal deterministically before a confirmed command can change the plan.

Provider isolation supports portability, avoids provider SDK leakage into domain code, and makes testing practical: a fake `LLMClient` can supply controlled structured responses for deterministic tests, while behavioral tests can exercise the real agent/provider integration separately.

## Proposed technology stack

The following is an implementation plan, not implemented software.

| Layer | Proposed technology | Design rationale |
|---|---|---|
| Frontend | React + TypeScript | Supports interactive calendar, pantry, recipe, grocery, profile, and plan-change views with typed UI models. |
| Backend/API | Python + FastAPI | Provides a typed, documented HTTP boundary for shared application use cases. |
| CLI | Python + Typer | Exposes the major MealIQ use cases through a scriptable command interface without duplicating business logic. |
| Database | PostgreSQL | Fits relational profiles, inventory, recipes, plans, grocery lists, and change records. |
| ORM/data access | SQLAlchemy | Implements repository persistence boundaries and transaction-oriented data access. |
| Validation/data models | Pydantic | Validates API DTOs and schema-constrained LLM proposals before domain processing. |
| LLM integration | External provider API behind `LLMClient` / `ProviderLLMAdapter` | Keeps agent/domain code provider-neutral and testable. |
| Recipe and price sources | Repository plus provider adapters | Supports curated local data and later external data sources through stable internal contracts. |
| Testing | pytest | Supports Stage 3 unit, integration, fake-adapter, and deterministic-service tests. |

No queue, cache, message broker, or additional infrastructure is part of the Stage 1 design because the current requirements do not require it.

## Overall architecture

```text
React GUI                     Python Typer CLI
     \                           /
      \      FastAPI boundary   /
       ---- MealPlanningFacade ----
              | application services
    AgentController / ProfileService / PantryService / MealPlanService
              |
        MealPlanningAgent ---- ToolManager ---- RecipeTool / NutritionTool /
              |                                  GroceryTool / SubstitutionTool
       PromptBuilder
              |
          LLMClient <- ProviderLLMAdapter <- External LLM API
              |
 deterministic validators and services ---- SQLAlchemy repositories ---- PostgreSQL
```

`MealPlanningFacade` is the shared application boundary for GUI and CLI use cases. FastAPI routes are delivery adapters around that boundary rather than an alternate business-logic path. Application services coordinate domain operations; repositories isolate persistence; the agent proposes decisions through stable tool and LLM interfaces; deterministic services calculate and validate facts.

## Responsibility split

| Agent/LLM responsibilities | Deterministic responsibilities |
|---|---|
| Interpret natural-language requests and detect missing information | Validate request fields, identifiers, quantities, units, and structured outputs |
| Select/order tools and synthesize their results | Retrieve/filter stored recipes by explicit predicates |
| Rank compatible candidates and explain soft-constraint trade-offs | Calculate nutrition, grocery quantities, price estimates, and duplicate consolidation |
| Propose custom recipes, substitutions, and plan changes | Enforce allergies/restrictions, cooking-time and budget rules, persistence, undo, and event publication |
| Propose a minimal adaptation after a relevant change | Determine affected plan/list state and preserve unchanged state on validation failure |

## Interfaces, tools, and external resources

`ToolManager` exposes the tools available to the agent: `RecipeTool`, `NutritionTool`, `GroceryTool`, and `SubstitutionTool`. Tool results are internal typed results rather than free-form text. `RecipeDataSource` and `ExternalRecipeAdapter` isolate recipe-provider differences; analogous price/catalog adapters may provide indicative cost data. The database and external APIs are resources accessed through interfaces, allowing controlled fakes in tests.

## Scope and assumptions

- The primary Stage 2 scope is one household profile and one active plan, while repository interfaces remain multi-profile capable.
- Recipe and indicative ingredient-price data are curated or supplied through adapters. Price is an estimate, not a store checkout guarantee.
- Allergies and dietary restrictions are hard constraints. Explicit nutrition goals, time limits, and budget limits are validated planning constraints; food preferences are soft constraints that may be traded off only with explanation.
- Expiration dates are optional. Planning does not deduct inventory; inventory changes occur only through an explicit confirmed update.
- Nutrition summaries are informational and depend on available ingredient data. Missing values remain labeled incomplete.
- Every LLM-derived proposal undergoes deterministic validation and user confirmation before it is applied to the active plan.
