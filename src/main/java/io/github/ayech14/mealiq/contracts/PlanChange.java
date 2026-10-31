package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.ChangeType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A proposed change to a plan. It never changes the plan itself (D03): it is validated,
 * previewed, and only applied by a command after the user confirms.
 *
 * <p>A change carries either a proposal to preview, or a clarification question when the
 * request was ambiguous (for example, which dinner to replace).
 */
public record PlanChange(UUID id, ChangeType changeType, List<UUID> targetMealIds, PlanningProposal proposal,
                         String rationale, String clarificationQuestion) {

    public PlanChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(changeType, "changeType");
        targetMealIds = targetMealIds == null ? List.of() : List.copyOf(targetMealIds);
        rationale = rationale == null ? "" : rationale;
        if (proposal == null && (clarificationQuestion == null || clarificationQuestion.isBlank())) {
            throw new IllegalArgumentException("A PlanChange needs either a proposal or a clarification question");
        }
    }

    public static PlanChange proposed(ChangeType type, List<UUID> targets, PlanningProposal proposal,
                                      String rationale) {
        return new PlanChange(UUID.randomUUID(), type, targets, proposal, rationale, null);
    }

    public static PlanChange clarification(ChangeType type, String question) {
        return new PlanChange(UUID.randomUUID(), type, List.of(), null, "", question);
    }

    public boolean needsClarification() {
        return proposal == null;
    }
}
