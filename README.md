# MealIQ

> **AI meal planning and grocery optimization for real-world constraints.**

MealIQ is a planned AI-agent-based meal-planning system for individuals who want to organize meals and groceries around nutrition goals, dietary restrictions, allergies, pantry inventory, budget, and available cooking time. It is designed as a practical planning assistant, not a medical or clinical nutrition service.

**Project status:** Stage 1 - software and agent design complete. The repository currently contains design documentation and version-controlled UML sources; no production frontend, backend, CLI, database, or LLM integration has been implemented.

## Contents

- [Problem and users](#problem-and-users)
- [Why an agent](#why-an-agent)
- [Core capabilities](#core-capabilities)
- [Example planning scenario](#example-planning-scenario)
- [How MealIQ works](#how-mealiq-works)
- [AI and external-provider integration](#ai-and-external-provider-integration)
- [Architecture and interfaces](#architecture-and-interfaces)
- [Technology plan](#technology-plan)
- [Design patterns](#design-patterns)
- [Project stages](#project-stages)
- [Design documentation](#design-documentation)
- [Responsible use and limitations](#responsible-use-and-limitations)
- [AI-assisted development disclosure](#ai-assisted-development-disclosure)

## Problem and users

Meal planning becomes a constraint-satisfaction problem when personal goals, allergies, disliked foods, ingredients already at home, a weekly budget, and uneven day-to-day cooking time all matter at once. Recipe browsing alone does not calculate shortages, consolidate a grocery list, explain trade-offs, or safely adapt a plan after circumstances change.

MealIQ serves an individual household meal planner who wants a coherent weekly plan and practical grocery list without repeatedly reconciling these details by hand. The system keeps the person in control: plan changes and substitutions are previewed before they alter an active plan.

## Why an agent

MealIQ is not a one-shot recipe chatbot. Its planned `MealPlanningAgent` interprets natural-language requests, retrieves relevant facts through tools, chooses a planning policy, proposes structured changes, and coordinates multi-step work such as plan generation followed by grocery projection and budget evaluation. It also responds to a changed pantry, budget, time limit, or food preference by identifying the affected portions of an existing plan.

The agent proposes and explains decisions. Deterministic services calculate nutrition, quantities, and costs; validate allergies, dietary rules, time, and budget; persist approved state; and consolidate grocery items. This separation makes decisions auditable and keeps arithmetic and hard constraints outside LLM generation.

## Core capabilities

| ID | Capability | Planned outcome |
|---|---|---|
| F01 | User goals and preferences | A reusable profile of goals, dietary restrictions, allergies, likes/dislikes, meals per day, budget, and time limits. |
| F02 | Pantry inventory management | Current ingredient quantities, units, and optional expiry information. |
| F03 | Recipe search and recommendation | Compatible, ranked recipes using pantry, nutrition, dietary, time, and budget context. |
| F04 | AI recipe generation | A validated structured recipe when retrieval cannot satisfy a request. |
| F05 | Weekly meal-plan generation | A multi-day meal plan with rationale and visible constraint conflicts. |
| F06 | Nutrition analysis | Deterministic calories, protein, carbohydrate, and fat summaries. |
| F07 | Grocery-list generation | Pantry-aware ingredient shortages for a selected plan. |
| F08 | Grocery-list optimization | Consolidated quantities, price estimate, and budget status. |
| F09 | Ingredient substitution | Constraint-aware alternatives for unavailable or unsuitable ingredients. |
| F10 | Natural-language plan modification | Structured, previewed changes such as “make Wednesday dinner vegetarian.” |
| F11 | Cooking-time-aware planning | Per-day or per-meal time limits respected during planning and revision. |
| F12 | Budget-aware planning | Budget evaluated across selection, grocery planning, and substitutions. |
| F13 | Dynamic plan adaptation | Targeted adaptation after relevant inventory, preference, time, or budget changes. |

## Example planning scenario

> “I want high-protein meals this week. I have chicken, rice, eggs, Greek yogurt, and spinach; I avoid mushrooms; my grocery budget is $100; and I only have 20 minutes to cook tonight.”

MealIQ is designed to translate this into a structured planning context, inspect the pantry and recipe sources, rank candidates using the applicable planning strategy, calculate nutrition and missing ingredients deterministically, evaluate the grocery estimate, and return either a validated plan or a transparent explanation of a conflict. A later request such as “replace Friday's dinner with something cheaper” becomes a previewed `PlanChange`, not an unverified text response.

## How MealIQ works

```text
GUI or CLI request
       |
MealPlanningFacade -> AgentController -> MealPlanningAgent
       |                                  |
       |                            ToolManager / LLMClient
       |                                  |
deterministic services <----- structured PlanningProposal or PlanChange
       |
validated MealPlan and GroceryList
```

1. The GUI or CLI sends the same request DTOs through `MealPlanningFacade`.
2. `AgentController` gathers profile, pantry, plan, and relevant conversation context.
3. `MealPlanningAgent` selects tools and a planning strategy, then requests a structured proposal.
4. Recipe, nutrition, grocery, and substitution tools provide constrained facts; the external LLM API assists with interpretation, planning, and decision proposals.
5. `ProposalValidator`, `ConstraintValidator`, and deterministic services validate the proposal before `MealPlanService` applies a confirmed result.

## AI and external-provider integration

Stage 2 is designed to integrate an external LLM API. OpenAI is the initial planned provider, with the exact model selected and documented during implementation. The application remains portable: `MealPlanningAgent` depends only on the provider-neutral `LLMClient` interface, while `ProviderLLMAdapter` translates structured requests and responses to the selected provider API/SDK. An alternative adapter can support another provider, such as Anthropic or Gemini, without changing agent or domain logic.

The LLM receives a purpose-built prompt from `PromptBuilder` plus relevant, bounded context. It returns schema-constrained data rather than directly mutating application state:

```text
LLM structured response -> PlanningProposal / PlanChange
                         -> ProposalValidator + ConstraintValidator
                         -> deterministic nutrition, cost, grocery, and rule checks
                         -> confirmed command execution
```

Recipe and indicative price data also enter through provider-neutral source adapters. External responses are treated as inputs to validate, not as an authority over inventory, arithmetic, or hard dietary constraints.

### Agent tools

| Tool | Role | Deterministic boundary |
|---|---|---|
| `RecipeTool` | Retrieves recipe candidates from repositories or external sources. | Filters and maps data to internal recipe objects. |
| `NutritionTool` | Supplies meal/plan nutrient summaries. | `NutritionService` performs calculations. |
| `GroceryTool` | Projects shortages and purchases for a plan. | `GroceryService` subtracts pantry stock and consolidates quantities. |
| `SubstitutionTool` | Finds potential substitutions. | `ConstraintValidator` enforces restrictions, time, nutrition, and budget checks. |

## Architecture and interfaces

MealIQ is designed as a modular application with two interface adapters and one shared application boundary.

| Layer | Planned responsibilities |
|---|---|
| GUI | React calendar, pantry, recipe, grocery, profile, and plan-change views. |
| CLI | Typer commands for the same major functions and natural-language modifications. |
| API/application boundary | FastAPI routes and `MealPlanningFacade` expose the shared use cases without UI-specific business logic. |
| Agent layer | `AgentController`, `MealPlanningAgent`, `PromptBuilder`, `ConversationMemory`, `ToolManager`, `LLMClient`. |
| Domain/services | Profile, pantry, meal-plan, nutrition, grocery, budget, validation, and command services. |
| Data/integrations | SQLAlchemy repositories over PostgreSQL plus LLM/recipe/price provider adapters. |

The [class diagram](docs/stage1/diagrams/class-diagram.mmd) and [sequence diagrams](docs/stage1/diagrams/) are the canonical, version-controlled UML sources.

## Technology plan

| Area | Planned technology | Purpose |
|---|---|---|
| Frontend | React + TypeScript | Graphical planning, pantry, recipe, and grocery interfaces. |
| Backend/API | Python + FastAPI | Typed HTTP API and application boundary for GUI and CLI clients. |
| CLI | Python + Typer | Scriptable access to the same application use cases. |
| Database | PostgreSQL | Durable profiles, pantry items, recipes, plans, and grocery lists. |
| Data access | SQLAlchemy | Repository persistence mapping and transaction boundaries. |
| Validation/data models | Pydantic | API DTO validation and structured LLM proposal models. |
| LLM | External API through `LLMClient` / `ProviderLLMAdapter` | Provider-neutral agent reasoning and structured output. |
| Testing | pytest | Later deterministic unit/integration tests and adapter fakes. |

## Design patterns

The Stage 1 design applies six patterns to concrete design problems:

- **Strategy:** interchangeable nutrition-goal, budget, and time-aware recipe ranking policies.
- **Factory Method:** a strategy factory creates the concrete strategy selected for a planning mode.
- **Command:** previewable, auditable, undoable meal-plan changes.
- **Observer:** pantry/profile changes notify plan- and grocery-impact observers without direct coupling.
- **Facade:** GUI and CLI call one shared application boundary.
- **Adapter:** LLM, recipe, and price provider APIs are translated to stable internal interfaces.

Full participants and rationale are documented in [design patterns](docs/stage1/design-patterns.md).

## Project stages

| Stage | Scope | Status |
|---|---|---|
| Stage 1 | Features, architecture, UML, patterns, decisions, and traceability | Complete design package |
| Stage 2 | Python/FastAPI, React, Typer, PostgreSQL, SQLAlchemy, Pydantic, and external LLM API implementation | Planned |
| Stage 3 | pytest coverage for deterministic components and KUMA behavioral validation for the agent | Planned |

## Design documentation

| Artifact | Description |
|---|---|
| [Stage 1 report](docs/stage1/stage1-report.md) | Public index of the complete design package and consistency audit. |
| [Project overview](docs/stage1/project-overview.md) | Problem, users, agent, LLM integration, architecture, stack, and scope. |
| [Feature specifications](docs/stage1/feature-specifications.md) | Inputs, outputs, workflows, UI access, classifications, and alternatives for F01-F13. |
| [Use cases](docs/stage1/use-cases.md) | Major user/system interactions and exception paths. |
| [Design patterns](docs/stage1/design-patterns.md) | Pattern problems, participants, responsibilities, and rationale. |
| [Traceability](docs/stage1/traceability.md) | Feature-to-use-case-to-class-to-method-to-sequence mapping and realization. |
| [Design decisions](docs/stage1/design-decisions.md) | Architecture decisions, validation boundaries, provider portability, and testability. |
| [UML sources](docs/stage1/diagrams/) | Mermaid use-case, class, and sequence diagrams. |

## Repository layout

```text
docs/
├── stage1/                 Stage 1 design package
│   └── diagrams/           Mermaid UML sources
└── dev/                    Local course reference PDFs (not tracked)
```

## Responsible use and limitations

- MealIQ provides meal-planning assistance, not medical, allergy-treatment, or professional dietary advice.
- Allergies and dietary restrictions are modeled as hard planning constraints, but users remain responsible for verifying ingredients, labels, cross-contamination risks, and suitability.
- Grocery prices are estimates based on available data, not retailer checkout prices.
- LLM-generated proposals require deterministic validation and user confirmation before plan mutation.
- External provider availability, recipe completeness, and ingredient-data quality can limit results; missing values are surfaced rather than fabricated.

## AI-assisted development disclosure

MealIQ is an EECS3311 Software Design course project. Stage 1 uses AI-assisted design work alongside human review and responsibility. The planned Stage 2 project record will identify the AI development tools and models used, their contribution, human review decisions, and resulting implementation changes. Stage 3 distinguishes conventional deterministic testing from behavioral testing of LLM-driven agent behavior.
