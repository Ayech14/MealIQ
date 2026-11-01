package io.github.ayech14.mealiq.services;

/** An expected, user-facing service failure that the GUI and CLI show as a message. */
public class ServiceException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ServiceException(String message) {
        super(message);
    }
}
