package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.commands.CompositeCommand;
import io.github.ayech14.mealiq.commands.PlanChangeCommand;
import io.github.ayech14.mealiq.commands.ReplaceMealCommand;
import io.github.ayech14.mealiq.commands.SubstituteIngredientCommand;
import io.github.ayech14.mealiq.contracts.PlanChange;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningProposal;
import io.github.ayech14.mealiq.domain.AppliedChange;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import io.github.ayech14.mealiq.persistence.MealPlanRepository;
import io.github.ayech14.mealiq.persistence.PlanChangeRepository;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores meal plans and applies confirmed changes to them (F05, F09, F10). Only validated
 * proposals reach {@link #createFromProposal}; a profile has one ACTIVE plan, and saving a
 * new one archives the previous one.
 *
 * <p>Every change runs as a {@link PlanChangeCommand} (Command, D03), and its swaps are
 * stored, so {@link #undoLastChange} can rebuild and undo it even in a later session.
 * Impact tracking follows with plan adaptation.
 */
public class MealPlanService {

    private final MealPlanRepository mealPlanRepository;
    private final RecipeRepository recipeRepository;
    private final PlanChangeRepository history;

    public MealPlanService(MealPlanRepository mealPlanRepository, RecipeRepository recipeRepository,
                           PlanChangeRepository history) {
        this.mealPlanRepository = mealPlanRepository;
        this.recipeRepository = recipeRepository;
        this.history = history;
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

    /**
     * Applies a confirmed change with a command, saves the plan and records the change for
     * undo. Substitution variants are stored first, because the plan refers to them.
     */
    public MealPlan executeChange(UUID planId, PlanChange change) {
        if (change.needsClarification()) {
            throw new IllegalArgumentException("A change that still needs clarification cannot be applied");
        }
        MealPlan plan = getPlan(planId);
        change.proposal().meals().stream().map(PlannedMeal::recipe)
                .filter(r -> r.source() == RecipeSource.VARIANT).distinct().forEach(recipeRepository::save);
        PlanChangeCommand command = createCommand(change, plan);
        command.execute(plan);
        mealPlanRepository.save(plan);
        history.append(planId, new AppliedChange(change.id(), change.changeType(), command.describe(),
                command.swaps()));
        return plan;
    }

    /**
     * Undoes the newest confirmed change of a plan by rebuilding its commands from storage.
     * @throws NotFoundException if the plan has no change to undo
     */
    public MealPlan undoLastChange(UUID planId) {
        MealPlan plan = getPlan(planId);
        AppliedChange last = history.removeLast(planId)
                .orElseThrow(() -> new NotFoundException("There is no change to undo"));
        List<PlanChangeCommand> commands = last.swaps().stream().map(swap -> swap.original() == null
                ? (PlanChangeCommand) ReplaceMealCommand.restored(swap)
                : SubstituteIngredientCommand.restored(swap)).toList();
        new CompositeCommand(commands).undo(plan);
        mealPlanRepository.save(plan);
        return plan;
    }

    /** Confirmed changes of a plan that can still be undone, oldest first. */
    public List<AppliedChange> changeHistory(UUID planId) {
        return history.findAll(planId);
    }

    /** One command per changed meal, wrapped so the change executes and undoes as a unit. */
    PlanChangeCommand createCommand(PlanChange change, MealPlan plan) {
        List<PlanChangeCommand> commands = new ArrayList<>();
        for (PlannedMeal proposed : change.proposal().meals()) {
            PlannedMeal current = plan.findMeal(proposed.id());
            commands.add(switch (change.changeType()) {
                case REPLACE_MEAL -> new ReplaceMealCommand(proposed.id(), proposed.recipe());
                case SUBSTITUTE_INGREDIENT -> substitution(current.recipe(), proposed);
                case UPDATE_CONSTRAINT -> throw new IllegalArgumentException(
                        "Constraint updates are applied through plan adaptation");
            });
        }
        return new CompositeCommand(commands);
    }

    /** The ingredient swap that turns the current recipe into the proposed variant. */
    private static SubstituteIngredientCommand substitution(Recipe current, PlannedMeal proposed) {
        List<IngredientRequirement> before = current.ingredients();
        List<IngredientRequirement> after = proposed.recipe().ingredients();
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            Ingredient original = before.get(i).ingredient();
            Ingredient substitute = after.get(i).ingredient();
            if (!original.id().equals(substitute.id())) {
                return new SubstituteIngredientCommand(proposed.id(), original, substitute);
            }
        }
        throw new IllegalArgumentException("The proposed recipe for " + current.name() + " substitutes nothing");
    }
}
