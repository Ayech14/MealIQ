package io.github.ayech14.mealiq.services;

public class NotFoundException extends ServiceException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
