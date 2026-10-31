package io.github.ayech14.mealiq.persistence;

/** A database failure, wrapped so services do not depend on JDBC exception types. */
public class PersistenceException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistenceException(String message) {
        super(message);
    }
}
