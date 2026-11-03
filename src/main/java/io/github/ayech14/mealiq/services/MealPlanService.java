package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.commands.CompositeCommand;
import io.github.ayech14.mealiq.commands.PlanChangeCommand;
import io.github.ayech14.mealiq.commands.ReplaceMealCommand;
import io.github.ayech14.mealiq.commands.SubstituteIngredientCommand;
import io.github.ayech14.mealiq.commands.UpdatePlanConstraintCommand;
import io.github.ayech14.mealiq.contracts.PlanChange;
import io.github.ayech14.mealiq.contracts.PlanImpact;
import io.github.ayech14.mealiq.contracts.PlanRequest;
import io.github.ayech14.mealiq.contracts.PlanningProposal;
import io.github.ayech14.mealiq.domain.AppliedChange;
import io.github.ayech14.mealiq.domain.ChangeType;
import io.github.ayech14.mealiq.domain.ImpactCause;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.IngredientRequirement;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.RecipeSource;
import io.github.ayech14.mealiq.events.PlanRelevantChange;
import io.github.ayech14.mealiq.persistence.MealPlanRepository;
import io.github.ayech14.mealiq.persistence.PlanChangeRepository;
import io.github.ayech14.mealiq.persistence.PlanImpactRepository;
import io.github.ayech14.mealiq.persistence.RecipeRepository;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Stores meal plans and applies confirmed changes to them (F05, F09, F10). Only validated
 * proposals reach {@link #createFromProposal}; a profile has one ACTIVE plan, and saving a
 * new one archives the previous one.
 *
 * <p>Every change runs as a {@link PlanChangeCommand} (Command, D03), and its swaps are
 * stored, so {@link #undoLastChange} can rebuild and undo it even in a later session.
 *
 * <p>After a pantry or profile change, the impact on the plan is assessed and its cause
 * recorded (F13, D06); {@link #getPendingImpact} recomputes it from the current state.
 */
public class MealPlanService {

    private final MealPlanRepository mealPlanRepository;
    private final RecipeRepository recipeRepository;
    private final PlanChangeRepository history;
    private final PlanImpactRepository impacts;
    private final PlanImpactAssessor assessor;

    public MealPlanService(MealPlanRepository mealPlanRepository, RecipeRepository recipeRepository,
                           PlanChangeRepository history, PlanImpactRepository impacts, PlanImpactAssessor assessor) {
        this.mealPlanRepository = mealPlanRepository;
        this.recipeRepository = recipeRepository;
        this.history = history;
        this.impacts = impacts;
        this.assessor = assessor;
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
        if (change.changeType() == ChangeType.UPDATE_CONSTRAINT) {
            // One command per weekday, so each reads as an adaptation to that day's limit.
            Map<DayOfWeek, List<ReplaceMealCommand>> byDay = new TreeMap<>();
            for (PlannedMeal proposed : change.proposal().meals()) {
                DayOfWeek day = plan.findMeal(proposed.id()).date().getDayOfWeek();
                byDay.computeIfAbsent(day, d -> new ArrayList<>())
                        .add(new ReplaceMealCommand(proposed.id(), proposed.recipe()));
            }
            List<PlanChangeCommand> days = new ArrayList<>();
            byDay.forEach((day, replacements) -> days.add(new UpdatePlanConstraintCommand(day,
                    assessor.timeLimit(plan.profileId(), day).stream().boxed().findFirst().orElse(null),
                    replacements)));
            return new CompositeCommand(days);
        }
        List<PlanChangeCommand> commands = new ArrayList<>();
        for (PlannedMeal proposed : change.proposal().meals()) {
            PlannedMeal current = plan.findMeal(proposed.id());
            commands.add(change.changeType() == ChangeType.SUBSTITUTE_INGREDIENT
                    ? substitution(current.recipe(), proposed)
                    : new ReplaceMealCommand(proposed.id(), proposed.recipe()));
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

    /** What a single change means for the plan right now (F13). */
    public PlanImpact assessImpact(MealPlan plan, PlanRelevantChange event) {
        return assessor.assess(plan, List.of(causeOf(event)));
    }

    /** Remembers the causes of a non-empty impact until the plan is adapted. */
    public void recordImpact(MealPlan plan, PlanImpact impact) {
        if (!impact.isEmpty()) {
            impact.causes().forEach(cause -> impacts.record(plan.id(), cause));
        }
    }

    /**
     * The recorded impact, recomputed from the current plan, profile and pantry: it is empty
     * once the plan fits again, and comes back if an undo brings the problem back.
     */
    public Optional<PlanImpact> getPendingImpact(UUID planId) {
        List<ImpactCause> causes = impacts.findAll(planId);
        if (causes.isEmpty()) {
            return Optional.empty();
        }
        PlanImpact impact = assessor.assess(getPlan(planId), causes);
        return impact.isEmpty() ? Optional.empty() : Optional.of(impact);
    }

    static ImpactCause causeOf(PlanRelevantChange event) {
        Object ingredient = event.details().get("ingredientId");
        return new ImpactCause(event.type(), ingredient instanceof UUID id ? id : null);
    }
}
