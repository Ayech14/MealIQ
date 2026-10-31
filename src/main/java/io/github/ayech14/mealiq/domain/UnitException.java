package io.github.ayech14.mealiq.domain;

/** An unknown unit, or a conversion between different dimensions (for example cups to grams). */
public class UnitException extends IllegalArgumentException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public UnitException(String message) {
        super(message);
    }
}
