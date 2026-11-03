-- MealIQ schema. Statements are idempotent (IF NOT EXISTS) and use SQL that both
-- PostgreSQL and H2 (PostgreSQL mode, used in tests) accept.
-- Multi-valued text fields (tags, allergens, instructions) are stored in one TEXT column,
-- encoded by TextListCodec.

CREATE TABLE IF NOT EXISTS ingredients (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120) NOT NULL UNIQUE,
    category        VARCHAR(60)  NOT NULL,
    allergens       TEXT         NOT NULL,
    -- nutrition per 100 g; NULL means the catalogue has no value (D09)
    calories        DOUBLE PRECISION,
    protein_g       DOUBLE PRECISION,
    carbs_g         DOUBLE PRECISION,
    fat_g           DOUBLE PRECISION,
    grams_per_unit  DOUBLE PRECISION,
    grams_per_ml    DOUBLE PRECISION,
    fdc_id          INTEGER
);

CREATE TABLE IF NOT EXISTS ingredient_substitutes (
    ingredient_id   UUID    NOT NULL REFERENCES ingredients (id) ON DELETE CASCADE,
    substitute_id   UUID    NOT NULL REFERENCES ingredients (id) ON DELETE CASCADE,
    position        INTEGER NOT NULL,
    PRIMARY KEY (ingredient_id, substitute_id)
);

CREATE TABLE IF NOT EXISTS recipes (
    id              UUID PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    meal_types      TEXT         NOT NULL,
    prep_minutes    INTEGER      NOT NULL,
    cook_minutes    INTEGER      NOT NULL,
    servings        INTEGER      NOT NULL,
    tags            TEXT         NOT NULL,
    source          VARCHAR(20)  NOT NULL,
    instructions    TEXT         NOT NULL
);

CREATE TABLE IF NOT EXISTS recipe_ingredients (
    recipe_id       UUID             NOT NULL REFERENCES recipes (id) ON DELETE CASCADE,
    position        INTEGER          NOT NULL,
    ingredient_id   UUID             NOT NULL REFERENCES ingredients (id),
    amount          DOUBLE PRECISION NOT NULL,
    unit            VARCHAR(20)      NOT NULL,
    optional        BOOLEAN          NOT NULL,
    PRIMARY KEY (recipe_id, position)
);

CREATE TABLE IF NOT EXISTS profiles (
    id                      UUID PRIMARY KEY,
    goal                    VARCHAR(20)   NOT NULL,
    daily_calorie_target    INTEGER,
    daily_protein_target_g  INTEGER,
    dietary_restrictions    TEXT          NOT NULL,
    allergies               TEXT          NOT NULL,
    disliked_foods          TEXT          NOT NULL,
    preferred_foods         TEXT          NOT NULL,
    meals_per_day           INTEGER       NOT NULL,
    weekly_budget           NUMERIC(10, 2) NOT NULL,
    max_cook_minutes        TEXT          NOT NULL
);

CREATE TABLE IF NOT EXISTS pantries (
    id              UUID PRIMARY KEY,
    profile_id      UUID NOT NULL UNIQUE REFERENCES profiles (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS pantry_items (
    pantry_id       UUID             NOT NULL REFERENCES pantries (id) ON DELETE CASCADE,
    ingredient_id   UUID             NOT NULL REFERENCES ingredients (id),
    position        INTEGER          NOT NULL,
    amount          DOUBLE PRECISION NOT NULL,
    unit            VARCHAR(20)      NOT NULL,
    expiry_date     DATE,
    PRIMARY KEY (pantry_id, ingredient_id)
);

CREATE TABLE IF NOT EXISTS meal_plans (
    id              UUID PRIMARY KEY,
    profile_id      UUID        NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    start_date      DATE        NOT NULL,
    days            INTEGER     NOT NULL,
    status          VARCHAR(20) NOT NULL
);

CREATE TABLE IF NOT EXISTS planned_meals (
    id              UUID PRIMARY KEY,
    plan_id         UUID        NOT NULL REFERENCES meal_plans (id) ON DELETE CASCADE,
    meal_date       DATE        NOT NULL,
    meal_type       VARCHAR(20) NOT NULL,
    recipe_id       UUID        NOT NULL REFERENCES recipes (id),
    servings        INTEGER     NOT NULL
);

CREATE TABLE IF NOT EXISTS grocery_lists (
    id              UUID PRIMARY KEY,
    plan_id         UUID           NOT NULL UNIQUE REFERENCES meal_plans (id) ON DELETE CASCADE,
    estimated_cost  NUMERIC(10, 2) NOT NULL,
    stale           BOOLEAN        NOT NULL
);

CREATE TABLE IF NOT EXISTS grocery_items (
    list_id             UUID             NOT NULL REFERENCES grocery_lists (id) ON DELETE CASCADE,
    position            INTEGER          NOT NULL,
    ingredient_id       UUID             NOT NULL REFERENCES ingredients (id),
    required_amount     DOUBLE PRECISION NOT NULL,
    required_unit       VARCHAR(20)      NOT NULL,
    in_pantry_amount    DOUBLE PRECISION NOT NULL,
    in_pantry_unit      VARCHAR(20)      NOT NULL,
    to_buy_amount       DOUBLE PRECISION NOT NULL,
    to_buy_unit         VARCHAR(20)      NOT NULL,
    estimated_price     NUMERIC(10, 2),
    PRIMARY KEY (list_id, position)
);

CREATE TABLE IF NOT EXISTS plan_changes (
    plan_id                  UUID         NOT NULL REFERENCES meal_plans (id) ON DELETE CASCADE,
    position                 INTEGER      NOT NULL,
    change_id                UUID         NOT NULL,
    change_type              VARCHAR(30)  NOT NULL,
    description              TEXT         NOT NULL,
    meal_id                  UUID         NOT NULL,
    before_recipe_id         UUID         NOT NULL REFERENCES recipes (id),
    after_recipe_id          UUID         NOT NULL REFERENCES recipes (id),
    original_ingredient_id   UUID         REFERENCES ingredients (id),
    substitute_ingredient_id UUID         REFERENCES ingredients (id),
    PRIMARY KEY (plan_id, position)
);

CREATE TABLE IF NOT EXISTS plan_impacts (
    plan_id         UUID        NOT NULL REFERENCES meal_plans (id) ON DELETE CASCADE,
    position        INTEGER     NOT NULL,
    event_type      VARCHAR(30) NOT NULL,
    ingredient_id   UUID        REFERENCES ingredients (id),
    PRIMARY KEY (plan_id, position)
);
