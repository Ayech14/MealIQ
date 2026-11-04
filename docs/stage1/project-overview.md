# MealIQ: project overview and architecture

## Problem, motivation, and target users

Meal planning is difficult when nutritional goals, dietary restrictions, allergies, disliked foods, pantry stock, spending limits, and uneven cooking time apply simultaneously. A recipe search can suggest individual dishes, but it does not reconcile a week of competing constraints, calculate shortages, consolidate purchases, or explain the consequences of a late change.

MealIQ is designed for an individual household meal planner who wants assistance organizing meals and groceries. It is a planning aid, not a medical, allergy-treatment, or clinical nutrition service. The system is appropriate for an agent because ordinary-language requests often imply a sequence of decisions: interpret the request, retrieve relevant recipes and facts, compare options under constraints, calculate concrete consequences, and propose an understandable next action.

## Agent description

`MealPlanningAgent` receives a normalized request and a bounded planning context containing the profile, pantry, active plan, and relevant prior confirmed decisions. It selects a planning strategy and returns a structured `PlanningProposal` or `PlanChange` containing proposed meals, substitutions, rationale, and conflicts. It does not directly persist or mutate a `MealPlan`.

Tool use is decided by the LLM, not hard-coded. The agent runs a bounded tool loop (`runToolLoop()`, at most `maxToolSteps` iterations):

1. `PromptBuilder.buildToolSelectionPrompt()` describes the goal, the context, the tools reported by `ToolManager.availableTools()`, and the results gathered so far.
2. `LLMClient.generateStructured()` returns the next action: either a tool call with arguments, or a signal that enough information has been gathered.
3. `ToolManager.isValidCall()` checks the tool name and arguments; only a valid call is executed. An invalid call is not executed, and the error is fed back to the LLM as an observation.
4. The `ToolResult` (for example ranked recipe candidates) is added to the results, and the loop repeats.

The loop ends when the LLM signals readiness or the step limit is reached. The agent then requests the final structured proposal. This makes tool selection, argument choice, and recovery from tool errors genuine LLM decisions that Stage 3 can test, while every tool remains deterministic code.

`ConversationMemory` stores only relevant prior requests and confirmed decisions for retrieval. Durable facts such as profiles, pantry items, recipes, meal plans, and grocery lists remain in repositories. This distinction prevents transient conversational context from becoming the source of truth for domain state.

## AI/LLM integration plan

MealIQ is designed to integrate an **external LLM API in Stage 2**. OpenAI is the initial planned provider: Stage 2 will use an OpenAI GPT model that supports structured (JSON-schema) outputs; the specific model version will be pinned and documented when implementation begins, chosen by structured-output reliability, cost, and availability. The architecture remains compatible with additional provider adapters, including Anthropic or Gemini.

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

Updated for the Stage 2 Java implementation; each change from the original design is recorded in [the Stage 2 report](../stage2/stage2-report.md#4-changes-and-rationale).

| Layer | Technology | Design rationale |
|---|---|---|
| GUI | Java 21 + JavaFX desktop app | Assistant conversation, profile, pantry, recipe, plan and grocery views; calls the facade in-process. |
| CLI | Java + picocli | Exposes the major MealIQ use cases through a scriptable command interface without duplicating business logic. |
| Database | PostgreSQL | Fits relational profiles, inventory, recipes, plans, grocery lists, and change records. |
| Data access | Hand-written JDBC repositories (H2 in PostgreSQL mode for tests) | Implements repository persistence boundaries and transaction-oriented data access. |
| Validation/data models | Java records + `JsonSchema` (strict structured output) | Validates schema-constrained LLM answers before domain processing. |
| LLM integration | OpenAI Java SDK behind `LLMClient` / `ProviderLLMAdapter` | Keeps agent/domain code provider-neutral and testable. |
| Recipe and price sources | Curated recipe catalogue; Statistics Canada prices behind `PriceDataSource` | Curated local data through stable internal contracts. |
| Testing | JUnit 5 with a scripted `LLMClient` | Unit, integration, fake-adapter, and deterministic-service tests. |

No queue, cache, message broker, or additional infrastructure is part of the Stage 1 design because the current requirements do not require it.

## Overall architecture

```text
JavaFX GUI                     picocli CLI
     |  in-process call             |  in-process call
     +------ MealPlanningFacade ----+
              | application services
    AgentController / ProfileService / PantryService / MealPlanService
              |
        MealPlanningAgent ---- ToolManager ---- RecipeTool / NutritionTool /
              |                                  GroceryTool / SubstitutionTool
       PromptBuilder
              |
          LLMClient <- ProviderLLMAdapter <- External LLM API
              |
 deterministic validators and services ---- JDBC repositories ---- PostgreSQL
```

`MealPlanningFacade` is the shared application boundary for GUI and CLI use cases. Both the JavaFX GUI and the CLI call it in-process; the HTTP layer (`MealPlanningApi`) planned for a browser GUI was not needed for a desktop GUI and was removed. Neither path contains business logic. Application services coordinate domain operations; repositories isolate persistence; the agent proposes decisions through stable tool and LLM interfaces; deterministic services calculate and validate facts.

## Responsibility split

| Agent/LLM responsibilities | Deterministic responsibilities |
|---|---|
| Interpret natural-language requests and detect missing information | Validate request fields, identifiers, quantities, units, and structured outputs |
| Select/order tools and synthesize their results | Retrieve/filter stored recipes by explicit predicates |
| Choose among strategy-ranked candidates when composing the plan (variety, balance) and explain soft-constraint trade-offs | Score and order candidates with the selected `PlanningStrategy`; calculate nutrition, grocery quantities, price estimates, and duplicate consolidation |
| Propose custom recipes, substitutions, and plan changes | Enforce allergies/restrictions, cooking-time and budget rules, persistence, undo, and event publication |
| Propose a minimal adaptation after a relevant change | Determine affected plan/list state and preserve unchanged state on validation failure |

## Interfaces, tools, and external resources

`ToolManager` exposes the tools available to the agent: `RecipeTool`, `NutritionTool`, `GroceryTool`, and `SubstitutionTool`. Tool results are internal typed results rather than free-form text. `PriceDataSource` and `StatCanPriceDataSource` isolate the price data (the external recipe adapter of the original design was not built; recipes come from the catalogue and AI generation). The database and external APIs are resources accessed through interfaces, allowing controlled fakes in tests.

## Scope and assumptions

- The primary Stage 2 scope is one household profile and one active plan, while repository interfaces remain multi-profile capable.
- Recipe and indicative ingredient-price data are curated or supplied through adapters. Price is an estimate, not a store checkout guarantee.
- Allergies and dietary restrictions are hard constraints. Explicit nutrition goals, time limits, and budget limits are validated planning constraints; food preferences are soft constraints that may be traded off only with explanation.
- Expiration dates are optional. Planning does not deduct inventory; inventory changes occur only through an explicit confirmed update.
- Nutrition summaries are informational and depend on available ingredient data. Missing values remain labeled incomplete. Where nutrition data comes from, and how ingredients are matched, is recorded in design decision D09.
- Every LLM-derived proposal undergoes deterministic validation and user confirmation before it is applied to the active plan.
