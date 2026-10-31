package io.github.ayech14.mealiq.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Supported measurement units. Each unit belongs to one dimension and has a size in that
 * dimension's base unit (grams, millilitres or single items). Converting between
 * dimensions needs ingredient data and is done by {@link Ingredient#toGrams(Quantity)}.
 */
public enum Unit {
    MG(Dimension.MASS, 0.001, "mg", "milligram", "milligrams"),
    G(Dimension.MASS, 1.0, "g", "gram", "grams", "gr"),
    KG(Dimension.MASS, 1000.0, "kg", "kilogram", "kilograms", "kgs"),
    OZ(Dimension.MASS, 28.349523125, "oz", "ounce", "ounces"),
    LB(Dimension.MASS, 453.59237, "lb", "pound", "pounds", "lbs"),
    ML(Dimension.VOLUME, 1.0, "ml", "milliliter", "milliliters", "millilitre", "millilitres"),
    L(Dimension.VOLUME, 1000.0, "l", "liter", "liters", "litre", "litres"),
    TSP(Dimension.VOLUME, 4.92892159375, "tsp", "teaspoon", "teaspoons"),
    TBSP(Dimension.VOLUME, 14.78676478125, "tbsp", "tablespoon", "tablespoons"),
    CUP(Dimension.VOLUME, 236.5882365, "cup", "cups"),
    FL_OZ(Dimension.VOLUME, 29.5735295625, "fl_oz", "fl oz", "floz"),
    UNIT(Dimension.COUNT, 1.0, "unit", "units", "piece", "pieces", "pc", "pcs", "each", "whole", "item", "items"),
    DOZEN(Dimension.COUNT, 12.0, "dozen");

    public enum Dimension { MASS, VOLUME, COUNT }

    private static final Map<String, Unit> BY_NAME = new HashMap<>();

    static {
        for (Unit unit : values()) {
            for (String name : unit.names) {
                BY_NAME.put(name, unit);
            }
        }
    }

    private final Dimension dimension;
    private final double baseFactor;
    private final String[] names;

    Unit(Dimension dimension, double baseFactor, String... names) {
        this.dimension = dimension;
        this.baseFactor = baseFactor;
        this.names = names;
    }

    /** Parses a unit name or alias, ignoring case ("Grams" becomes G). */
    public static Unit parse(String text) {
        Unit unit = text == null ? null : BY_NAME.get(text.strip().toLowerCase(Locale.ROOT));
        if (unit == null) {
            throw new UnitException("Unknown unit: '" + text + "'");
        }
        return unit;
    }

    public static boolean isKnown(String text) {
        return text != null && BY_NAME.containsKey(text.strip().toLowerCase(Locale.ROOT));
    }

    public Dimension dimension() {
        return dimension;
    }

    /** Size of one of this unit in the dimension's base unit. */
    public double baseFactor() {
        return baseFactor;
    }

    /** Canonical short name, as stored and displayed. */
    public String symbol() {
        return names[0];
    }

    @Override
    public String toString() {
        return symbol();
    }
}
