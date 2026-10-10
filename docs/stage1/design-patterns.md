# Design patterns

MealIQ applies six course patterns to identifiable design problems. The patterns preserve a clear separation between interface delivery, agent decision-making, deterministic validation, and external integrations.

## Strategy - planning and ranking policy

**Design problem.** A high-protein, gain-oriented plan, a budget-first plan, and a time-first plan can rank the same compatible recipes differently. Embedding all ranking policies in conditional branches would make `MealPlanningAgent` difficult to extend and test.

**Participants and roles.** `PlanningStrategy` defines `rank(candidates, context)`. `NutritionGoalStrategy`, `BudgetStrategy`, and `TimeAwareStrategy` implement alternative ranking policies. `MealPlanningAgent` is the context that requests a strategy, while `ConstraintValidator` remains the hard-constraint gate.

**Rationale.** Each strategy ranks candidates and exposes its trade-offs without changing agent orchestration. Adding a policy remains local to a new implementation instead of modifying a growing selection block. Strategy supports polymorphism, focused tests, and an explicit distinction between preference ranking and non-negotiable validation.

**UML evidence.** Class diagrams: services and patterns view (`PlanningStrategy` realized by three strategies, and `MealPlanningAgent` ..> `PlanningStrategy` labelled `ranks with`). Sequence diagrams: SD01 and SD05 (`rank(candidates, context)` on `strategy:PlanningStrategy`).

## Factory Method - planning-strategy creation

**Design problem.** Agent code needs a `PlanningStrategy` without being coupled to concrete strategy construction or configuration.

**Participants and roles.** Abstract creator `PlanningStrategyFactory` declares the factory method `createStrategy(mode)`. `DefaultPlanningStrategyFactory` overrides that method and creates the appropriate concrete product: `NutritionGoalStrategy`, `BudgetStrategy`, or `TimeAwareStrategy`. `MealPlanningAgent` depends on the creator abstraction and receives only the `PlanningStrategy` product.

**Rationale.** This is the *parameterized* form of Factory Method described by Gamma et al.: the abstract creator declares the factory method, and the concrete creator overrides it to decide, from the `PlanningMode` argument, which concrete product to create. A different policy set, such as a test factory that always returns a fixed strategy, can be supplied by adding another `PlanningStrategyFactory` subclass, without changing the agent. The class diagram makes the concrete creator-to-product dependencies explicit. Without it, agent or UI code would instantiate concrete strategies, coupling planning orchestration to construction details and making replacement/configuration harder.

**UML evidence.** Class diagrams: services and patterns view and complete view (`DefaultPlanningStrategyFactory` --▷ `PlanningStrategyFactory`, with «create» dependencies to each concrete strategy). Sequence diagrams: SD01 messages 7-8 and SD05 messages 22-23 (`createStrategy()`).

## Command - previewable and reversible plan changes

**Design problem.** Natural-language modifications and dynamic adaptation require auditable, confirmable, undoable changes rather than direct `MealPlan` mutation by the agent.

**Participants and roles.** `PlanChangeCommand` declares `execute(plan)` and `undo(plan)`. `ReplaceMealCommand`, `SubstituteIngredientCommand`, and `UpdatePlanConstraintCommand` are concrete commands. `MealPlanService` invokes commands; `MealPlan` is the receiver; `PlanChange` records a structured proposed change.

**Rationale.** A command is validated and previewed before execution, and the service can retain enough state for undo. The agent returns command data rather than performing mutations. Without Command, confirmation, auditability, reversible changes, and focused plan-change tests would be distributed through agent and UI code.

**UML evidence.** Class diagrams: services and patterns view (`MealPlanService` holds a `history` of 0..* `PlanChangeCommand`, three realizations, and a `receiver` dependency to `MealPlan`). Sequence diagram: SD03 messages 31-34 (`createCommand()`, `execute(plan)`, `replaceMeal()`).

## Observer - plan and grocery impact notification

**Design problem.** Pantry and profile updates can affect an active plan and its grocery list, but the services that own pantry/profile state should not know planning or grocery-update details.

**Participants and roles.** `PlanRelevantChangePublisher` defines `subscribe`, `unsubscribe`, and `publish`. `PantryService` and `ProfileService` are concrete publishers. `PlanImpactObserver` defines `onPlanRelevantChange(event)`. `MealPlanImpactObserver` assesses plan impact and records it through `MealPlanService` (the LLM adaptation runs later, on the user's request, so observers stay fast and do not depend on `AgentController`); `GroceryListImpactObserver` marks the related grocery list stale through `GroceryService`. `PlanRelevantChange` is the event payload.

**Rationale.** A successful source update publishes one typed event to interested observers. The observer graph keeps dependency direction away from pantry/profile services and supports later observers without changing publishers. The adaptation sequence shows both observers receiving the event; the grocery list is refreshed after a confirmed plan change.

**UML evidence.** Class diagrams: services and patterns view (publisher interface realized by `ProfileService` and `PantryService`, `notifies` 1 to 0..* `PlanImpactObserver`, two concrete observers). Sequence diagram: SD04 messages 6-14 (publish, both observers, `assessImpact()`, `recordImpact()`).

## Facade - shared application boundary

**Design problem.** The React GUI and Typer CLI require access to the same coordinated use cases without duplicating orchestration or knowing agent, repository, and service internals.

**Participants and roles.** `MealPlanningFacade` is the facade. `MealPlanningCLI` is a direct client; `MealPlanningGUI` is a client through `MealPlanningApi`, the FastAPI route layer, which forwards requests unchanged. `AgentController`, profile/pantry/meal-plan services, and grocery services form the subsystem behind the facade. FastAPI route handlers are delivery adapters that call the facade.

**Rationale.** The facade presents operations such as `generateWeeklyPlan`, `modifyPlan`, and `generateOptimizedGroceryList` through one stable application interface. Without it, two interfaces could evolve independent business rules or couple directly to the agent and repositories.

**UML evidence.** Class diagrams: architecture view (`MealPlanningCLI` and `MealPlanningApi` associated only with `MealPlanningFacade`, which associates with the controller and services). Sequence diagrams: every SD routes the boundary through `:MealPlanningFacade`.

## Adapter - external LLM and data providers

**Design problem.** External LLM APIs/SDKs and recipe/price data sources expose provider-specific request, response, authentication, and error models. MealIQ domain code needs stable typed contracts and test seams.

**Participants and roles.** `LLMClient` is the target interface used by `MealPlanningAgent`. `ProviderLLMAdapter` is the adapter; it translates prompts, structured-output schemas, responses, and provider errors to the `LLMClient` contract. The selected external LLM API/SDK is the adaptee. `RecipeDataSource`/`ExternalRecipeAdapter` (adapting the external recipe provider API) and `PriceDataSource`/`ExternalPriceAdapter` (adapting an indicative price catalog) apply the same pattern to recipe and price data.

**Rationale.** The initial Stage 2 plan uses an OpenAI GPT model with structured-output support behind `ProviderLLMAdapter`, while other adapters can support Anthropic or Gemini. Domain classes remain independent of a provider SDK, and tests can substitute a fake `LLMClient` with controlled structured results. Without Adapter, provider details would leak into the agent, inhibit portability, and complicate deterministic validation and failure tests.

**UML evidence.** Class diagrams: architecture view (`ProviderLLMAdapter` ..▷ `LLMClient` with `adaptee` association to `ExternalLLMProviderAPI`; same structure for recipe and price adapters). Sequence diagrams: SD01 and SD03 (`generateStructured()` then `createStructuredResponse()`), SD05 (`find()` then `searchRecipes()`).
