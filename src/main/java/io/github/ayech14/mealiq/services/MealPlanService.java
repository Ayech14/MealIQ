package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningProposal;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.persistence.MealPlanRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores meal plans (F05). Only validated proposals reach {@link #createFromProposal}; a
 * profile has one ACTIVE plan, and saving a new one archives the previous one.
 *
 * <p>Plan changes, undo and impact tracking follow with plan modification and adaptation.
 */
public class MealPlanService {

    private final MealPlanRepository mealPlanRepository;

    public MealPlanService(MealPlanRepository mealPlanRepository) {
        this.mealPlanRepository = mealPlanRepository;
    }

    public MealPlan getPlan(UUID planId) {
        return mealPlanRepository.findById(planId)
                .orElseThrow(() -> new NotFoundException("Plan " + planId + " not found"));
    }

    public Optional<MealPlan> getActivePlan(UUID profileId) {
        return mealPlanRepository.findActiveByProfileId(profileId);
    }

    /** Saves a validated proposal as the profile's active plan. */
    public MealPlan createFromProposal(PlanRequest request, PlanningProposal proposal) {
        getActivePlan(request.profileId()).ifPresent(previous -> {
            previous.status(PlanStatus.ARCHIVED);
            mealPlanRepository.save(previous);
        });
        MealPlan plan = new MealPlan(request.profileId(), request.startDate(), request.days(), proposal.meals());
        plan.status(PlanStatus.ACTIVE);
        mealPlanRepository.save(plan);
        return plan;
    }
}
