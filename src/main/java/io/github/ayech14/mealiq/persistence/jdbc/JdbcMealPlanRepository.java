package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.domain.MealPlan;
import io.github.ayech14.mealiq.domain.MealType;
import io.github.ayech14.mealiq.domain.PlanStatus;
import io.github.ayech14.mealiq.domain.PlannedMeal;
import io.github.ayech14.mealiq.persistence.MealPlanRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcMealPlanRepository implements MealPlanRepository {

    private static final String[] COLUMNS = {"profile_id", "start_date", "days", "status"};

    private final Database database;

    public JdbcMealPlanRepository(Database database) {
        this.database = database;
    }

    @Override
    public Optional<MealPlan> findById(UUID planId) {
        return findOne("SELECT id, profile_id, start_date, days, status FROM meal_plans WHERE id = ?", planId);
    }

    @Override
    public Optional<MealPlan> findActiveByProfileId(UUID profileId) {
        return findOne("SELECT id, profile_id, start_date, days, status FROM meal_plans "
                + "WHERE profile_id = ? AND status = ? ORDER BY start_date DESC LIMIT 1",
                profileId, PlanStatus.ACTIVE.name());
    }

    @Override
    public void save(MealPlan plan) {
        Object[] values = {plan.profileId(), plan.startDate(), plan.days(), plan.status().name()};
        database.transaction(connection -> {
            Sql.upsert(connection, "meal_plans", COLUMNS, values, "id", plan.id());
            Sql.update(connection, "DELETE FROM planned_meals WHERE plan_id = ?", plan.id());
            for (PlannedMeal meal : plan.meals()) {
                Sql.update(connection, "INSERT INTO planned_meals "
                        + "(id, plan_id, meal_date, meal_type, recipe_id, servings) VALUES (?, ?, ?, ?, ?, ?)",
                        meal.id(), plan.id(), meal.date(), meal.mealType().name(), meal.recipe().id(),
                        meal.servings());
            }
            return null;
        });
    }

    private Optional<MealPlan> findOne(String sql, Object... params) {
        return database.read(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection, sql, params);
                 ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID id = row.getObject("id", UUID.class);
                return Optional.of(new MealPlan(id, row.getObject("profile_id", UUID.class),
                        row.getObject("start_date", LocalDate.class), row.getInt("days"),
                        PlanStatus.valueOf(row.getString("status")), meals(connection, id)));
            }
        });
    }

    /** Meals in date order, then breakfast, lunch, dinner, snack. */
    private static List<PlannedMeal> meals(Connection connection, UUID planId) throws SQLException {
        record Row(UUID id, LocalDate date, MealType type, UUID recipeId, int servings) {
        }
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = Sql.prepare(connection,
                "SELECT id, meal_date, meal_type, recipe_id, servings FROM planned_meals WHERE plan_id = ?", planId);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new Row(result.getObject("id", UUID.class), result.getObject("meal_date", LocalDate.class),
                        MealType.valueOf(result.getString("meal_type")), result.getObject("recipe_id", UUID.class),
                        result.getInt("servings")));
            }
        }
        JdbcLoader loader = new JdbcLoader(connection);
        List<PlannedMeal> meals = new ArrayList<>();
        for (Row row : rows) {
            meals.add(new PlannedMeal(row.id(), row.date(), row.type(), loader.recipe(row.recipeId()),
                    row.servings()));
        }
        meals.sort(Comparator.comparing(PlannedMeal::date).thenComparing(PlannedMeal::mealType));
        return meals;
    }
}
