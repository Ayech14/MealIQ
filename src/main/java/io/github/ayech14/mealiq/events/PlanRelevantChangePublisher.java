package io.github.ayech14.mealiq.events;

/** Publisher role, realized by ProfileService and PantryService. */
public interface PlanRelevantChangePublisher {

    void subscribe(PlanImpactObserver observer);

    void unsubscribe(PlanImpactObserver observer);

    void publish(PlanRelevantChange event);
}
