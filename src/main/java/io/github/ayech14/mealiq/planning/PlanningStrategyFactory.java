package io.github.ayech14.mealiq.planning;

import io.github.ayech14.mealiq.contracts.PlanningContext;
import io.github.ayech14.mealiq.domain.PlanningMode;

/**
 * Creates the strategy for a planning task (Factory Method). Callers ask for a strategy by
 * context; subclasses decide which class each mode maps to.
 */
public abstract class PlanningStrategyFactory {

    /** A strategy for the request's mode, already configured with the context's profile. */
    public PlanningStrategy strategyFor(PlanningContext context) {
        PlanningStrategy strategy = createStrategy(context.request().mode());
        strategy.configure(context.profile());
        return strategy;
    }

    protected abstract PlanningStrategy createStrategy(PlanningMode mode);
}
