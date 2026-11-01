package io.github.ayech14.mealiq.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The subscriber list each publisher holds as its {@code observers} field. Kept in one
 * class so both services share the same notification rules instead of copying them.
 */
public final class ObserverList {

    private static final System.Logger LOG = System.getLogger(ObserverList.class.getName());

    private final List<PlanImpactObserver> observers = new CopyOnWriteArrayList<>();

    public void add(PlanImpactObserver observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public void remove(PlanImpactObserver observer) {
        observers.remove(observer);
    }

    /**
     * Notifies every observer in subscription order. The change is already saved when this
     * runs, so a failing observer is logged and does not stop the others or undo the update.
     */
    public void notifyAll(PlanRelevantChange event) {
        for (PlanImpactObserver observer : observers) {
            try {
                observer.onPlanRelevantChange(event);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Observer " + observer.getClass().getSimpleName()
                        + " failed on " + event.type(), e);
            }
        }
    }
}
