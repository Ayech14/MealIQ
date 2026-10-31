package io.github.ayech14.mealiq.domain;

/** An unknown unit, or a conversion between different dimensions (for example cups to grams). */
public class UnitException extends IllegalArgumentException {

    public UnitException(String message) {
        super(message);
    }
}
