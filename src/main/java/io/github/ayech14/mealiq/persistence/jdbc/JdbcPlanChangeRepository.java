package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.domain.AppliedChange;
import io.github.ayech14.mealiq.domain.ChangeType;
import io.github.ayech14.mealiq.domain.MealSwap;
import io.github.ayech14.mealiq.persistence.PlanChangeRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One row per swapped meal; the rows of one change share its change_id. */
public final class JdbcPlanChangeRepository implements PlanChangeRepository {

    private final Database database;

    public JdbcPlanChangeRepository(Database database) {
        this.database = database;
    }

    @Override
    public void append(UUID planId, AppliedChange change) {
        database.transaction(connection -> {
            int position;
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT COALESCE(MAX(position), -1) + 1 FROM plan_changes WHERE plan_id = ?", planId);
                 ResultSet row = statement.executeQuery()) {
                row.next();
                position = row.getInt(1);
            }
            for (MealSwap swap : change.swaps()) {
                Sql.update(connection, "INSERT INTO plan_changes (plan_id, position, change_id, change_type, "
                        + "description, meal_id, before_recipe_id, after_recipe_id, original_ingredient_id, "
                        + "substitute_ingredient_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        planId, position++, change.changeId(), change.type().name(), change.description(),
                        swap.mealId(), swap.before().id(), swap.after().id(),
                        swap.original() == null ? null : swap.original().id(),
                        swap.substitute() == null ? null : swap.substitute().id());
            }
            return null;
        });
    }

    @Override
    public Optional<AppliedChange> removeLast(UUID planId) {
        return database.transaction(connection -> {
            List<AppliedChange> all = load(connection, planId);
            if (all.isEmpty()) {
                return Optional.<AppliedChange>empty();
            }
            AppliedChange last = all.getLast();
            Sql.update(connection, "DELETE FROM plan_changes WHERE plan_id = ? AND change_id = ?", planId,
                    last.changeId());
            return Optional.of(last);
        });
    }

    @Override
    public List<AppliedChange> findAll(UUID planId) {
        return database.read(connection -> load(connection, planId));
    }

    private static List<AppliedChange> load(Connection connection, UUID planId) throws SQLException {
        record Row(UUID changeId, ChangeType type, String description, UUID mealId, UUID before, UUID after,
                   UUID original, UUID substitute) {
        }
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = Sql.prepare(connection, "SELECT change_id, change_type, description, "
                + "meal_id, before_recipe_id, after_recipe_id, original_ingredient_id, substitute_ingredient_id "
                + "FROM plan_changes WHERE plan_id = ? ORDER BY position", planId);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new Row(result.getObject("change_id", UUID.class),
                        ChangeType.valueOf(result.getString("change_type")), result.getString("description"),
                        result.getObject("meal_id", UUID.class), result.getObject("before_recipe_id", UUID.class),
                        result.getObject("after_recipe_id", UUID.class),
                        result.getObject("original_ingredient_id", UUID.class),
                        result.getObject("substitute_ingredient_id", UUID.class)));
            }
        }
        JdbcLoader loader = new JdbcLoader(connection);
        Map<UUID, List<MealSwap>> swaps = new LinkedHashMap<>();
        Map<UUID, Row> first = new LinkedHashMap<>();
        for (Row row : rows) {
            first.putIfAbsent(row.changeId(), row);
            swaps.computeIfAbsent(row.changeId(), id -> new ArrayList<>()).add(new MealSwap(row.mealId(),
                    loader.recipe(row.before()), loader.recipe(row.after()),
                    row.original() == null ? null : loader.ingredient(row.original()),
                    row.substitute() == null ? null : loader.ingredient(row.substitute())));
        }
        List<AppliedChange> changes = new ArrayList<>();
        first.forEach((id, row) -> changes.add(new AppliedChange(id, row.type(), row.description(), swaps.get(id))));
        return changes;
    }
}
