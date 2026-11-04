# MealIQ

> **An AI agent for meal planning and grocery optimization under real-world constraints.**

MealIQ plans meals and groceries around your nutrition goals, allergies and diet, food likes and dislikes, what is already in your pantry, a weekly budget, and how much time you have to cook each day. You tell it what you want, in your own words or through its screens. Its agent searches recipes with tools, ranks them with deterministic strategies, proposes a plan or a change, and applies it only after you confirm.

MealIQ is a planning aid, not medical or dietary advice.

EECS3311 Software Design course project, York University.

## Contents

- [What it does](#what-it-does)
- [Why it is an agent](#why-it-is-an-agent)
- [Requirements](#requirements)
- [Setup](#setup)
- [Running MealIQ](#running-mealiq)
- [Example session](#example-session)
- [Architecture](#architecture)
- [Design patterns](#design-patterns)
- [Data sources](#data-sources)
- [Tests](#tests)
- [Repository layout](#repository-layout)
- [Documentation](#documentation)
- [Responsible use and limitations](#responsible-use-and-limitations)

## What it does

| ID | Feature | What you get |
|---|---|---|
| F01 | Goals and preferences | Goal, calorie and protein targets, diet rules, allergies, likes, dislikes, meals per day, weekly budget, cooking minutes per weekday |
| F02 | Pantry | What you have at home, with quantities and expiry dates; expired items are not counted |
| F03 | Recipe search and recommendation | The agent searches, ranks by nutrition, time or cost, and explains its picks |
| F04 | AI recipe generation | A new recipe from catalogue ingredients you can eat, previewed before saving |
| F05 | Weekly meal plans | A validated multi-day plan with daily nutrition against your targets |
| F06 | Nutrition analysis | Calories, protein, carbohydrates and fat from USDA data, per serving, day and plan |
| F07 | Grocery list | What to buy for the plan after your pantry, in one line per ingredient |
| F08 | Grocery optimization | Prices, the budget result with the exact shortfall, and cheaper meals when over budget |
| F09 | Ingredient substitution | Allowed replacements for an ingredient across the plan |
| F10 | Plan changes in plain words | "Make Wednesday's dinner vegetarian": previewed, confirmed, undoable |
| F11 | Cooking-time-aware planning | Each day's time limit respected in ranking, planning and changes |
| F12 | Budget-aware planning | Cost-aware ranking, and plans checked against the budget |
| F13 | Plan adaptation | After a pantry or profile change, the affected meals are flagged and can be fixed |
| N01 | Assistant | One place to ask for any of the above in your own words |

## Why it is an agent

MealIQ is not a chatbot that forwards text to an LLM. Its `MealPlanningAgent`:

- **Uses tools.** It decides which tool to call next, and with which arguments, in a bounded loop: recipe search, nutrition analysis, grocery list, ingredient substitution. Invalid tool calls are rejected and the error is fed back so the agent can correct itself.
- **Chooses among ranked options.** It picks from candidates that deterministic planning strategies have already ranked, and explains its choice using only facts it was given.
- **Proposes; it never applies.** Plans and changes come back as structured proposals that validators check against allergies, diet, cooking time and budget. A rejected plan is retried once with the reasons. A change is applied only after you confirm it, through a command you can undo.
- **Reacts to change.** When your pantry or profile changes, the affected meals are flagged at once without calling the LLM, and the agent can adapt only those meals.
- **Remembers.** It keeps earlier decisions in memory for follow-up requests.

All arithmetic, which covers nutrition, quantities, prices and validation, is done by deterministic code, never by the LLM.

## Requirements

- **Java 21:** a JDK. The Maven wrapper (`./mvnw`) downloads everything else on first use.
- **Docker:** runs the PostgreSQL 16 database, through `docker compose`.
- **An OpenAI API key:** needed for the agent features. Profile, pantry, recipe browsing, nutrition and grocery lists work without one.

## Setup

1. Create your local configuration from the example, which holds placeholders only:

   ```bash
   cp .env.example .env
   ```

2. Open `.env` and set:
   - `LLM_API_KEY`: your OpenAI API key.
   - `LLM_MODEL`: the model to use. The example pins `gpt-5.4-mini-2026-03-17`.
   - `POSTGRES_PASSWORD` and `DATABASE_PASSWORD`: a local database password. Use the same value in both.

   `.env` is listed in `.gitignore` and must never be committed. If a key is ever exposed, revoke it in the OpenAI dashboard and create a new one.

3. Start the database, then create the tables and load the ingredient catalogue and recipes:

   ```bash
   docker compose up -d
   ./mealiq setup
   ```

   Running `setup` again is safe: existing data is kept and the catalogue is updated in place.

## Running MealIQ

Run these from the project folder; the first run compiles the project.

**Desktop app:**

```bash
./mealiq-gui
```

It opens on the **Assistant** tab, where you type what you want or pick a suggestion. The other tabs are **Profile**, **Pantry**, **Recipes**, **Plan** and **Groceries**. Use the **Profile** field at the top to switch between people.

**Command line:**

```bash
./mealiq --help
./mealiq ask "I'm vegetarian and allergic to peanuts"
```

| Command | Purpose |
|---|---|
| `ask "..."` | Ask the agent in your own words; changes are confirmed with `[y/N]` (`--yes` to skip) |
| `profile show` / `profile set ...` | Show or change goals, diet, allergies, budget and time limits |
| `pantry list` / `pantry set INGREDIENT AMOUNT UNIT` / `pantry remove INGREDIENT` | Manage the pantry |
| `ingredients [TEXT]` | Search the ingredient catalogue |
| `recipes list` / `find "..."` / `generate "..."` / `show "NAME"` | Browse, ask the agent, generate, see details |
| `plan generate` / `show` / `modify "..."` / `substitute INGREDIENT` / `cheaper` / `adapt` / `undo` / `history` | Plans and changes |
| `grocery generate` / `grocery show` | The priced shopping list and budget check |
| `--profile NAME` | Use another person's profile with any command |

Add `--show-steps` to agent commands to see the tool calls the agent made.

**Windows.** The two launch scripts are for macOS and Linux. On Windows, build the class path once with Maven and start Java directly, in PowerShell:

```powershell
.\mvnw.cmd -q compile dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt"
java -cp "target/classes;$(Get-Content target/classpath.txt)" io.github.ayech14.mealiq.presentation.cli.MealPlanningCLI --help
java -cp "target/classes;$(Get-Content target/classpath.txt)" io.github.ayech14.mealiq.presentation.gui.MealIQDesktop
```

**Stopping.** Close the app. Stop the database with `docker compose down`; your data stays in a Docker volume.

## Example session

```text
$ ./mealiq ask "I'm vegetarian and allergic to peanuts, my budget is 60 dollars a week"
Update your profile?
  Diet: vegetarian
  Allergies: peanut
  Weekly budget: $60.00
Apply? [y/N] y

$ ./mealiq ask "plan 2 days of quick meals"
  2026-11-03 breakfast: Spinach scrambled eggs on toast
  2026-11-03 lunch: Black bean quesadillas
  ...

$ ./mealiq ask "I ran out of eggs"
Update your pantry?
  egg: none left
Apply? [y/N] y
Note: Day 1 (2026-11-03) breakfast: Spinach scrambled eggs on toast uses egg, which is no longer in your pantry.

$ ./mealiq ask "fix my plan"
Proposed change:
  2026-11-03 breakfast: Spinach scrambled eggs on toast -> Tofu scramble
Apply this change? [y/N] y
```

## Architecture

```text
 JavaFX GUI (presentation.gui)      picocli CLI (presentation.cli)
                 \                       /
                  MealPlanningFacade  (application)
                  |                 \
          AgentController            services: Profile, Pantry, MealPlan, Grocery,
                  |                  Budget, Nutrition, ProposalValidator,
          MealPlanningAgent (agent)  ConstraintValidator, PlanImpactAssessor
           |      |        \
     LLMClient  ToolManager  PlanningStrategyFactory -> PlanningStrategy (planning)
   (ProviderLLM  (Recipe, Nutrition,
    Adapter)      Grocery, Substitution tools)
                  |
     repositories (persistence): JDBC on PostgreSQL; H2 in tests
```

- The interfaces only collect input and show results; all rules sit behind the facade.
- The agent depends only on the `LLMClient` interface. OpenAI SDK types stay inside `ProviderLLMAdapter`.
- Plan changes are `PlanChangeCommand`s run by `MealPlanService`, and they are stored so undo works across sessions.
- Pantry and profile changes notify `MealPlanImpactObserver` and `GroceryListImpactObserver`.

## Design patterns

| Pattern | Where | Problem it solves |
|---|---|---|
| Strategy | `PlanningStrategy`, `NutritionGoalStrategy`, `TimeAwareStrategy`, `BudgetStrategy` | Interchangeable ranking policies for the agent |
| Factory Method | `PlanningStrategyFactory`, `DefaultPlanningStrategyFactory` | The right strategy for a planning mode, configured for the profile |
| Command | `PlanChangeCommand`, `ReplaceMealCommand`, `SubstituteIngredientCommand`, `UpdatePlanConstraintCommand`, `CompositeCommand` | Confirmed, undoable plan changes kept separate from AI proposals |
| Observer | `PlanRelevantChangePublisher`, `PantryService`, `ProfileService`, `MealPlanImpactObserver`, `GroceryListImpactObserver` | Flag affected plans and lists without coupling services to them |
| Facade | `MealPlanningFacade` | One boundary for the GUI and the CLI |
| Adapter | `LLMClient`/`ProviderLLMAdapter`, `PriceDataSource`/`StatCanPriceDataSource` | Keep provider SDKs and data formats out of domain code |

## Data sources

- **Nutrition:** USDA FoodData Central SR Legacy (2018-04), curated to 146 ingredients with per-100 g values and gram weights (`src/main/resources/catalogue/ingredients.csv`, built by `scripts/build_catalogue.py`).
- **Prices:** Statistics Canada, *Monthly average retail prices for selected products* (table 18-10-0245-01), Canada averages for August 2026, for 63 ingredients (`catalogue/prices.csv`, built by `scripts/build_prices.py`). Ingredients without a matching product have no price, and estimates are shown as lower bounds (`$x+`).
- **Recipes:** 30 curated recipes using only catalogue ingredients (`catalogue/recipes.json`), plus any AI-generated recipes you save.

## Tests

```bash
./mvnw test
```

The JUnit 5 suite covers domain values, every service and validator, the strategies, the commands, and the agent's tool loop driven by a scripted LLM (no network). It also covers the facade flows and the CLI commands end to end. Repository tests run on an in-memory H2 database. To run them against PostgreSQL too, set `MEALIQ_TEST_DATABASE_URL` (for example `jdbc:postgresql://localhost:5432/mealiq_test`).

## Repository layout

```text
README.md                    This file
pom.xml, mvnw, mvnw.cmd      Maven build and wrapper
mealiq, mealiq-gui           Launch scripts (macOS, Linux)
docker-compose.yml           Local PostgreSQL
.env.example                 Configuration template (placeholders only)
scripts/                     Data build scripts (USDA catalogue, Statistics Canada prices)
src/main/java/io/github/ayech14/mealiq/
    agent/                   MealPlanningAgent, tools, prompts, memory
    application/             MealPlanningFacade, AgentController
    commands/                Plan change commands
    config/                  .env loading
    contracts/               Requests, proposals, responses
    domain/                  Profile, pantry, recipes, plans, quantities
    events/                  Observer types
    integration/             Price data adapter
    llm/                     LLMClient, OpenAI adapter, JSON schemas
    persistence/             Repositories, JDBC, catalogue loaders
    planning/                Strategies and factory
    presentation/cli/        Command line
    presentation/gui/        JavaFX desktop app
    services/                Deterministic services and validators
src/main/resources/          Catalogue data and database schema
src/test/java/               JUnit tests
docs/stage1/                 Design (specifications, use cases, patterns, decisions, UML)
docs/stage2/                 Implementation report
```

## Documentation

- [Stage 1 design](docs/stage1/stage1-report.md): features F01-F13, use cases, design patterns, design decisions D01-D09, traceability, UML.
- [Stage 2 implementation report](docs/stage2/stage2-report.md): feature-to-class mapping, implemented patterns, every change from the design and why.

## Responsible use and limitations

- MealIQ provides meal-planning assistance, not medical, allergy-treatment or professional dietary advice. Always check ingredients and labels yourself.
- Allergies and diet rules are hard constraints in planning, but they cover only the tags in the ingredient catalogue.
- Prices are public averages, not store checkout prices, and some ingredients have none.
- Plans use one serving per meal, so high calorie targets may not be reached; the app reports this.
- Recipe search covers the curated collection and saved generated recipes; there is no external recipe provider.
- AI proposals are always validated and need your confirmation before they change anything.
