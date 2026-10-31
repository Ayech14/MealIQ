package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.domain.WeightGoal;
import io.github.ayech14.mealiq.persistence.ProfileRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

public final class JdbcProfileRepository implements ProfileRepository {

    private static final String[] COLUMNS = {"goal", "daily_calorie_target", "daily_protein_target_g",
        "dietary_restrictions", "allergies", "disliked_foods", "preferred_foods", "meals_per_day", "weekly_budget",
        "max_cook_minutes"};

    private final Database database;

    public JdbcProfileRepository(Database database) {
        this.database = database;
    }

    @Override
    public Optional<UserProfile> findById(UUID profileId) {
        return database.read(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection,
                    "SELECT id, " + String.join(", ", COLUMNS) + " FROM profiles WHERE id = ?", profileId);
                 ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserProfile(profileId)
                        .goal(WeightGoal.valueOf(row.getString("goal")))
                        .dailyCalorieTarget(JdbcLoader.nullableInt(row, "daily_calorie_target"))
                        .dailyProteinTargetG(JdbcLoader.nullableInt(row, "daily_protein_target_g"))
                        .dietaryRestrictions(TextListCodec.decodeSet(row.getString("dietary_restrictions")))
                        .allergies(TextListCodec.decodeSet(row.getString("allergies")))
                        .dislikedFoods(TextListCodec.decodeSet(row.getString("disliked_foods")))
                        .preferredFoods(TextListCodec.decodeSet(row.getString("preferred_foods")))
                        .mealsPerDay(row.getInt("meals_per_day"))
                        .weeklyBudget(row.getBigDecimal("weekly_budget"))
                        .maxCookMinutes(TextListCodec.decodeDayMinutes(row.getString("max_cook_minutes"))));
            }
        });
    }

    @Override
    public void save(UserProfile profile) {
        Object[] values = {
            profile.goal().name(),
            profile.dailyCalorieTarget(),
            profile.dailyProteinTargetG(),
            TextListCodec.encodeSet(profile.dietaryRestrictions()),
            TextListCodec.encodeSet(profile.allergies()),
            TextListCodec.encodeSet(profile.dislikedFoods()),
            TextListCodec.encodeSet(profile.preferredFoods()),
            profile.mealsPerDay(),
            profile.weeklyBudget(),
            TextListCodec.encodeDayMinutes(profile.maxCookMinutes()),
        };
        database.transaction(connection -> {
            Sql.upsert(connection, "profiles", COLUMNS, values, "id", profile.id());
            return null;
        });
    }
}
