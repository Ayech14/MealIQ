package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Unit;
import io.github.ayech14.mealiq.persistence.PantryRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.UUID;

public final class JdbcPantryRepository implements PantryRepository {

    private final Database database;

    public JdbcPantryRepository(Database database) {
        this.database = database;
    }

    @Override
    public Pantry findByProfileId(UUID profileId) {
        return database.read(connection -> {
            UUID pantryId;
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT id FROM pantries WHERE profile_id = ?", profileId);
                 ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Pantry.emptyFor(profileId);
                }
                pantryId = row.getObject("id", UUID.class);
            }
            Pantry pantry = new Pantry(pantryId, profileId);
            JdbcLoader loader = new JdbcLoader(connection);
            record Line(UUID ingredientId, double amount, String unit, LocalDate expiry) {
            }
            java.util.List<Line> lines = new java.util.ArrayList<>();
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT ingredient_id, amount, unit, expiry_date FROM pantry_items "
                            + "WHERE pantry_id = ? ORDER BY position", pantryId);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    lines.add(new Line(rows.getObject("ingredient_id", UUID.class), rows.getDouble("amount"),
                            rows.getString("unit"), rows.getObject("expiry_date", LocalDate.class)));
                }
            }
            for (Line line : lines) {
                pantry.updateItem(new PantryItem(loader.ingredient(line.ingredientId()),
                        new Quantity(line.amount(), Unit.parse(line.unit())), line.expiry()));
            }
            return pantry;
        });
    }

    @Override
    public void save(Pantry pantry) {
        database.transaction(connection -> {
            UUID pantryId;
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT id FROM pantries WHERE profile_id = ?", pantry.profileId());
                 ResultSet row = statement.executeQuery()) {
                pantryId = row.next() ? row.getObject("id", UUID.class) : null;
            }
            if (pantryId == null) {
                pantryId = pantry.id();
                Sql.update(connection, "INSERT INTO pantries (id, profile_id) VALUES (?, ?)", pantryId,
                        pantry.profileId());
            }
            Sql.update(connection, "DELETE FROM pantry_items WHERE pantry_id = ?", pantryId);
            int position = 0;
            for (PantryItem item : pantry.items().values()) {
                Sql.update(connection, "INSERT INTO pantry_items "
                        + "(pantry_id, ingredient_id, position, amount, unit, expiry_date) VALUES (?, ?, ?, ?, ?, ?)",
                        pantryId, item.ingredient().id(), position++, item.quantity().amount(),
                        item.quantity().unit().symbol(), item.expiryDate());
            }
            return null;
        });
    }
}
