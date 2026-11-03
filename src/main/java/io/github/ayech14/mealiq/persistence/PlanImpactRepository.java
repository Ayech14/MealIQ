package io.github.ayech14.mealiq.persistence;

import io.github.ayech14.mealiq.domain.ImpactCause;
import java.util.List;
import java.util.UUID;

/**
 * The causes of impacts on a plan (F13). They stay with the plan; whether there is an impact
 * now is recomputed from them each time.
 */
public interface PlanImpactRepository {

    /** Adds a cause unless the plan already has the same one. */
    void record(UUID planId, ImpactCause cause);

    List<ImpactCause> findAll(UUID planId);
}
