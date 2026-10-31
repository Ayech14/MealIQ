package io.github.ayech14.mealiq.persistence.jdbc;

import io.github.ayech14.mealiq.config.AppConfig;
import io.github.ayech14.mealiq.persistence.PersistenceException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Opens JDBC connections and runs work in short transactions. Each repository call uses
 * its own connection, so no connection is held between user actions.
 */
public final class Database {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final String url;
    private final String user;
    private final String password;

    public Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public static Database from(AppConfig config) {
        return new Database(config.databaseUrl(), config.databaseUser(), config.databasePassword());
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** Runs read-only work with auto-commit. */
    public <T> T read(SqlWork<T> work) {
        try (Connection connection = open()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw new PersistenceException("Database read failed: " + e.getMessage(), e);
        }
    }

    /** Runs work in one transaction: committed if it returns, rolled back if it throws. */
    public <T> T transaction(SqlWork<T> work) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Database update failed: " + e.getMessage(), e);
        }
    }

    /** Creates any missing tables from {@code db/schema.sql}. Existing tables are left untouched. */
    public void createSchema() {
        String script = readSchemaScript();
        transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements(script)) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    /** Splits a script into statements, dropping {@code --} comments. */
    static java.util.List<String> statements(String script) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : script.split("\n")) {
            int comment = line.indexOf("--");
            withoutComments.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return java.util.Arrays.stream(withoutComments.toString().split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    static String readSchemaScript() {
        try (InputStream in = Database.class.getResourceAsStream("/db/schema.sql")) {
            if (in == null) {
                throw new PersistenceException("db/schema.sql is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PersistenceException("Cannot read db/schema.sql", e);
        }
    }
}
