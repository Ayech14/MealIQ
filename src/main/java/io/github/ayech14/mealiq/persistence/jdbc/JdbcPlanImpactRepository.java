package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.domain.ChangeEventType;
import io.github.ayech14.mealiq.domain.ImpactCause;
import io.github.ayech14.mealiq.persistence.PlanImpactRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JdbcPlanImpactRepository implements PlanImpactRepository {

    private final Database database;

    public JdbcPlanImpactRepository(Database database) {
        this.database = database;
    }

    @Override
    public void record(UUID planId, ImpactCause cause) {
        database.transaction(connection -> {
            List<ImpactCause> existing = load(connection, planId);
            if (existing.contains(cause)) {
                return null;
            }
            Sql.update(connection, "INSERT INTO plan_impacts (plan_id, position, event_type, ingredient_id) "
                    + "VALUES (?, ?, ?, ?)", planId, existing.size(), cause.type().name(), cause.ingredientId());
            return null;
        });
    }

    @Override
    public List<ImpactCause> findAll(UUID planId) {
        return database.read(connection -> load(connection, planId));
    }

    private static List<ImpactCause> load(Connection connection, UUID planId) throws SQLException {
        List<ImpactCause> causes = new ArrayList<>();
        try (PreparedStatement statement = Sql.prepare(connection,
                "SELECT event_type, ingredient_id FROM plan_impacts WHERE plan_id = ? ORDER BY position", planId);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                causes.add(new ImpactCause(ChangeEventType.valueOf(rows.getString("event_type")),
                        rows.getObject("ingredient_id", UUID.class)));
            }
        }
        return causes;
    }
}
