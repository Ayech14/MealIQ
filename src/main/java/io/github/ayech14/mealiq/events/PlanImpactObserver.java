package io.github.ayech14.mealiq.events;

/** Observer role. Implementations do fast, deterministic work only; no LLM calls (D06). */
@FunctionalInterface
public interface PlanImpactObserver {

    void onPlanRelevantChange(PlanRelevantChange event);
}
