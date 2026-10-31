package io.github.ayech14.mealiq.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.ChangeType;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlanningMode;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractsTest {

    static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    @Test
    void planRequestBoundsDays() {
        UUID profile = UUID.randomUUID();
        assertEquals(7, PlanRequest.week(profile, MONDAY, PlanningMode.BUDGET).days());
        assertThrows(IllegalArgumentException.class,
                () -> new PlanRequest(profile, MONDAY, 0, PlanningMode.BUDGET, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanRequest(profile, MONDAY, 15, PlanningMode.BUDGET, null));
    }

    @Test
    void recipeQueryValidatesAndBuilds() {
        assertThrows(IllegalArgumentException.class, () -> RecipeQuery.any().withMaxMinutes(0));
        RecipeQuery query = RecipeQuery.any().withMaxMinutes(20).including("chicken").excluding("rice");
        assertEquals(20, query.maxMinutes());
        assertEquals(List.of("chicken"), query.includeIngredients());
        assertEquals(List.of("rice"), query.excludeIngredients());
        assertFalse(query.allowGenerated());
    }

    @Test
    void contextKeepsLiveDomainObjects() {
        UserProfile profile = new UserProfile();
        Pantry pantry = Pantry.emptyFor(profile.id());
        MealPlan plan = new MealPlan(UUID.randomUUID(), profile.id(), MONDAY, 7, PlanStatus.ACTIVE, List.of());
        PlanningContext context = new PlanningContext(PlanRequest.week(profile.id(), MONDAY,
                PlanningMode.NUTRITION_GOAL), profile, pantry, plan, null, List.of(), List.of());
        assertSame(profile, context.profile());
        assertSame(plan, context.activePlan());
    }

    @Test
    void planChangeNeedsProposalOrQuestion() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlanChange(UUID.randomUUID(), ChangeType.REPLACE_MEAL, List.of(), null, "", " "));
        PlanChange question = PlanChange.clarification(ChangeType.REPLACE_MEAL, "Which dinner?");
        assertTrue(question.needsClarification());
        PlanChange proposed = PlanChange.proposed(ChangeType.REPLACE_MEAL, List.of(),
                new PlanningProposal(List.of(), PlanningMode.BUDGET, "cheaper", List.of()), "cheaper");
        assertFalse(proposed.needsClarification());
    }

    @Test
    void validationResultMerge() {
        ValidationResult merged = ValidationResult.ok("partial prices").merge(ValidationResult.failed("contains peanut"));
        assertFalse(merged.valid());
        assertEquals(List.of("contains peanut"), merged.violations());
        assertEquals(List.of("partial prices"), merged.warnings());
        assertThrows(IllegalArgumentException.class, () -> new ValidationResult(true, List.of("x"), List.of()));
    }

    @Test
    void emptyImpactAndResponseBuilder() {
        assertTrue(PlanImpact.NONE.isEmpty());
        assertFalse(new PlanImpact(List.of(), List.of(), List.of("budget exceeded")).isEmpty());
        AgentResponse response = AgentResponse.builder(ResponseStatus.CONFLICT).conflicts(List.of("over budget"))
                .build();
        assertEquals(ResponseStatus.CONFLICT, response.status());
        assertEquals(List.of("over budget"), response.conflicts());
        assertTrue(response.recipes().isEmpty());
    }
}
