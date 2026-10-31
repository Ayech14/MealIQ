package io.github.ayech14.mealiq.contracts;

import io.github.ayech14.mealiq.domain.BudgetStatus;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.Recipe;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import java.util.List;
import java.util.Objects;

/** What the facade returns to the GUI and CLI. Fields that do not apply are null or empty. */
public record AgentResponse(ResponseStatus status, MealPlan plan, PlanChange change, GroceryList groceryList,
                            BudgetStatus budgetStatus, List<Recipe> recipes, String rationale,
                            List<String> conflicts) {

    public AgentResponse {
        Objects.requireNonNull(status, "status");
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
        rationale = rationale == null ? "" : rationale;
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public static Builder builder(ResponseStatus status) {
        return new Builder(status);
    }

    public static final class Builder {
        private final ResponseStatus status;
        private MealPlan plan;
        private PlanChange change;
        private GroceryList groceryList;
        private BudgetStatus budgetStatus;
        private List<Recipe> recipes = List.of();
        private String rationale = "";
        private List<String> conflicts = List.of();

        private Builder(ResponseStatus status) {
            this.status = status;
        }

        public Builder plan(MealPlan value) {
            plan = value;
            return this;
        }

        public Builder change(PlanChange value) {
            change = value;
            return this;
        }

        public Builder groceryList(GroceryList value) {
            groceryList = value;
            return this;
        }

        public Builder budgetStatus(BudgetStatus value) {
            budgetStatus = value;
            return this;
        }

        public Builder recipes(List<Recipe> value) {
            recipes = value;
            return this;
        }

        public Builder rationale(String value) {
            rationale = value;
            return this;
        }

        public Builder conflicts(List<String> value) {
            conflicts = value;
            return this;
        }

        public AgentResponse build() {
            return new AgentResponse(status, plan, change, groceryList, budgetStatus, recipes, rationale, conflicts);
        }
    }
}
