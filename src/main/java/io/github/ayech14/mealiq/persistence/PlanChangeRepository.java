package io.github.ayech14.mealiq.persistence;

import io.github.ayech14.mealiq.domain.AppliedChange;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The confirmed changes of each plan, newest last, so undo works across sessions: the CLI
 * runs each command in a new process (D03).
 */
public interface PlanChangeRepository {

    void append(UUID planId, AppliedChange change);

    /** Removes and returns the newest change of a plan, if any. */
    Optional<AppliedChange> removeLast(UUID planId);

    /** Every change of a plan, oldest first. */
    List<AppliedChange> findAll(UUID planId);
}
