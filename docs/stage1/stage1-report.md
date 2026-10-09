# MealIQ - Stage 1 Project Design Report

**Course:** EECS3311 Software Design. 
**Project:** MealIQ - AI Meal Planning & Grocery Optimization Agent. 
**Stage:** 1 - Software and Agent Design. 
**Status:** Stage 1 design package; implementation is planned for Stage 2.

## Executive summary

MealIQ is a domain-specific agent that creates and revises meal plans and grocery lists while balancing pantry inventory, nutritional preferences/goals, dietary restrictions/allergies, disliked foods, budget, meals/day, and changing cooking-time limits. It is designed as an agentic software system, not a chatbot: it interprets natural language, selects and uses tools, retrieves recipes, proposes recipes/substitutions, performs multi-step planning, and adapts existing plans. The Stage 2 architecture integrates an external LLM API through a provider-neutral `LLMClient` and `ProviderLLMAdapter`; deterministic services own quantities, arithmetic, validation, and persistence.

This report is a navigable master document. The linked artifacts below are part of the report and are the authoritative detail for Stage 1.

## 1. Project overview, problem, users, agent, and model plan

See [project overview and architecture](project-overview.md). It defines the problem/motivation, primary users, agent responsibilities, external LLM API integration plan, provider-neutral `LLMClient`, Python/FastAPI technology plan, architecture, responsibility split, scope, and assumptions.

## 2. Detailed feature specifications

MealIQ specifies exactly thirteen substantive features (F01-F13), exceeding the course minimum without filler. Each includes GUI interaction, inputs/outputs, classification, workflow, and alternatives: [feature specifications](feature-specifications.md).

## 3. Architecture and design patterns

The React GUI and Python Typer CLI are thin clients of `MealPlanningFacade`, exposed through planned FastAPI delivery adapters. Application services coordinate domain state, SQLAlchemy repositories isolate persistence, and the agent works through tool interfaces and an external-API-backed `LLMClient` abstraction. The full rationale for Strategy, Factory Method, Command, Observer, Facade, and Adapter is in [design patterns](design-patterns.md). This exceeds the required five meaningful patterns.

## 4. UML use cases and descriptions

The UMLet [use-case diagram](diagrams/umlet/use-case-diagram.uxf) identifies the Meal Planner and supporting external LLM API/recipe-price providers. Its PNG export is available at [use-case-diagram.png](diagrams/png/use-case-diagram.png). [Detailed use cases](use-cases.md) cover profile/constraints, pantry, recipe retrieval/generation, weekly planning, nutrition, grocery generation/optimization, substitution, natural-language modification, and dynamic adaptation.

## 5. UML class diagram

The [consolidated class diagram](diagrams/umlet/class-diagram-complete.uxf) ([PNG](diagrams/png/class-diagram-complete.png)) places every class and relationship from the four detailed UMLet views on one large canvas. Its color-coded regions preserve the original layouts and arrow endpoints: architecture and integrations; planning services and patterns; domain entities; and proposals, events, and repositories. The four focused diagrams remain available for legible inspection of their detail: [architecture and integrations](diagrams/umlet/class-diagram.uxf) ([PNG](diagrams/png/class-diagram.png)), [planning services](diagrams/umlet/class-diagram-services.uxf) ([PNG](diagrams/png/class-diagram-services.png)), [domain entities](diagrams/umlet/class-diagram-domain.uxf) ([PNG](diagrams/png/class-diagram-domain.png)), and [proposals, events, and repositories](diagrams/umlet/class-diagram-values.uxf) ([PNG](diagrams/png/class-diagram-values.png)).

## 6. UML sequence diagrams

The workflows use class-diagram names and show boundary, controller, agent, tools/services, return values, and alternatives. SD01 also shows persistence through `MealPlanRepository`:

- [SD01 - Generate weekly meal plan](diagrams/umlet/sequence-meal-plan.uxf) ([PNG](diagrams/png/sequence-meal-plan.png))
- [SD02 - Generate and optimize grocery list](diagrams/umlet/sequence-grocery.uxf) ([PNG](diagrams/png/sequence-grocery.png))
- [SD03 - Modify meal plan using natural language](diagrams/umlet/sequence-modification.uxf) ([PNG](diagrams/png/sequence-modification.png))
- [SD04 - Adapt after pantry/constraint change](diagrams/umlet/sequence-adaptation.uxf) ([PNG](diagrams/png/sequence-adaptation.png))

## 7. Feature-to-design mapping and feature realization

The complete feature mapping plus implementation-oriented explanation of each F01-F13 is in [traceability](traceability.md). It provides feature, type, use case, classes, methods, sequence diagram, patterns, AI/deterministic responsibility, and error behavior.

## 8. Important design decisions, scope, and Stage 3 compatibility

See [design decisions and testability](design-decisions.md). It records design decisions (including constraint precedence and confirmation before mutation), deterministic test seams, planned KUMA behavioral requirements, and the deliberate Stage 1 boundary.

## 9. Consistency audit

| Check | Result |
|---|---|
| At least 10 meaningful features | Pass: 13, F01-F13 |
| GUI and CLI share business logic | Pass: both call `MealPlanningFacade` |
| Meaningful AI/agent behavior and external LLM API abstraction | Pass: `MealPlanningAgent`, tools, memory, `LLMClient`, `ProviderLLMAdapter` |
| Five meaningful course patterns | Pass: six documented patterns |
| Feature -> use case -> classes -> methods -> sequence traceability | Pass: complete F01-F13 table |
| Required major sequence workflows | Pass: SD01-SD04 |
| Nutrition, budget, pantry, restrictions, time, dynamic adaptation represented | Pass across features, classes, use cases, and sequences |
| Agent vs deterministic responsibilities separated | Pass: overview, features, traceability, decisions |
| Stage 2 implementation avoided | Pass: documentation/diagram sources only |

## Public repository scope

The repository contains public-facing Stage 1 design artifacts, editable UMLet `.uxf` diagrams, and PNG exports rendered by UMLet. UMLet is the single maintained diagram format. Local course reference PDFs remain outside the tracked project content through the `docs/dev/` ignore rule.
