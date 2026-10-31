package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.domain.GroceryItem;
import io.github.ayech14.mealiq.domain.GroceryList;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Unit;
import io.github.ayech14.mealiq.persistence.GroceryListRepository;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcGroceryListRepository implements GroceryListRepository {

    private final Database database;

    public JdbcGroceryListRepository(Database database) {
        this.database = database;
    }

    @Override
    public Optional<GroceryList> findByPlanId(UUID planId) {
        return database.read(connection -> {
            UUID listId;
            BigDecimal cost;
            boolean stale;
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT id, estimated_cost, stale FROM grocery_lists WHERE plan_id = ?", planId);
                 ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                listId = row.getObject("id", UUID.class);
                cost = row.getBigDecimal("estimated_cost");
                stale = row.getBoolean("stale");
            }
            record Line(UUID ingredientId, Quantity required, Quantity inPantry, Quantity toBuy, BigDecimal price) {
            }
            List<Line> lines = new ArrayList<>();
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT ingredient_id, required_amount, required_unit, in_pantry_amount, in_pantry_unit, "
                            + "to_buy_amount, to_buy_unit, estimated_price FROM grocery_items "
                            + "WHERE list_id = ? ORDER BY position", listId);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    lines.add(new Line(rows.getObject("ingredient_id", UUID.class),
                            quantity(rows, "required"), quantity(rows, "in_pantry"), quantity(rows, "to_buy"),
                            rows.getBigDecimal("estimated_price")));
                }
            }
            JdbcLoader loader = new JdbcLoader(connection);
            List<GroceryItem> items = new ArrayList<>();
            for (Line line : lines) {
                items.add(new GroceryItem(loader.ingredient(line.ingredientId()), line.required(), line.inPantry(),
                        line.toBuy(), line.price()));
            }
            return Optional.of(new GroceryList(listId, planId, items, cost, stale));
        });
    }

    @Override
    public void save(GroceryList list) {
        database.transaction(connection -> {
            UUID listId;
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT id FROM grocery_lists WHERE plan_id = ?", list.planId());
                 ResultSet row = statement.executeQuery()) {
                listId = row.next() ? row.getObject("id", UUID.class) : null;
            }
            if (listId == null) {
                listId = list.id();
                Sql.update(connection, "INSERT INTO grocery_lists (id, plan_id, estimated_cost, stale) "
                        + "VALUES (?, ?, ?, ?)", listId, list.planId(), list.estimatedCost(), list.isStale());
            } else {
                Sql.update(connection, "UPDATE grocery_lists SET estimated_cost = ?, stale = ? WHERE id = ?",
                        list.estimatedCost(), list.isStale(), listId);
            }
            Sql.update(connection, "DELETE FROM grocery_items WHERE list_id = ?", listId);
            int position = 0;
            for (GroceryItem item : list.items()) {
                Sql.update(connection, "INSERT INTO grocery_items (list_id, position, ingredient_id, "
                        + "required_amount, required_unit, in_pantry_amount, in_pantry_unit, to_buy_amount, "
                        + "to_buy_unit, estimated_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        listId, position++, item.ingredient().id(),
                        item.required().amount(), item.required().unit().symbol(),
                        item.inPantry().amount(), item.inPantry().unit().symbol(),
                        item.toBuy().amount(), item.toBuy().unit().symbol(),
                        item.estimatedPrice());
            }
            return null;
        });
    }

    private static Quantity quantity(ResultSet row, String prefix) throws java.sql.SQLException {
        return new Quantity(row.getDouble(prefix + "_amount"), Unit.parse(row.getString(prefix + "_unit")));
    }
}
