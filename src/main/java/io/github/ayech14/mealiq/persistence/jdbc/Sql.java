package io.github.ayech14.mealiq.persistence.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** Small JDBC helpers shared by the repositories. */
final class Sql {

    private Sql() {
    }

    /** Binds parameters by position; null values are bound as SQL NULL. */
    static PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) {
                statement.setNull(i + 1, Types.NULL);
            } else {
                statement.setObject(i + 1, params[i]);
            }
        }
        return statement;
    }

    static int update(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, params)) {
            return statement.executeUpdate();
        }
    }

    /**
     * Portable upsert: UPDATE by key, and INSERT when no row was updated. Both statements
     * take the key as the last parameter so one argument list serves both.
     */
    static void upsert(Connection connection, String table, String[] columns, Object[] values, String keyColumn,
                       Object key) throws SQLException {
        String assignments = String.join(" = ?, ", columns) + " = ?";
        Object[] updateParams = append(values, key);
        if (update(connection, "UPDATE " + table + " SET " + assignments + " WHERE " + keyColumn + " = ?",
                updateParams) == 0) {
            String placeholders = "?, ".repeat(columns.length) + "?";
            update(connection, "INSERT INTO " + table + " (" + String.join(", ", columns) + ", " + keyColumn
                    + ") VALUES (" + placeholders + ")", updateParams);
        }
    }

    private static Object[] append(Object[] values, Object last) {
        Object[] all = java.util.Arrays.copyOf(values, values.length + 1);
        all[values.length] = last;
        return all;
    }
}
