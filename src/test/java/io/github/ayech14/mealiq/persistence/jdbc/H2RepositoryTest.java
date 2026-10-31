package io.github.ayech14.mealiq.persistence.jdbc;

import java.util.UUID;

/** Runs the repository contract on an in-memory H2 database in PostgreSQL mode. */
class H2RepositoryTest extends RepositoryContractTest {

    static Database freshH2() {
        Database database = new Database("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        database.createSchema();
        return database;
    }

    @Override
    Database freshDatabase() {
        return freshH2();
    }
}
