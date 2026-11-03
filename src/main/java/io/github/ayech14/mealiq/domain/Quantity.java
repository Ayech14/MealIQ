package io.github.ayech14.mealiq.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** An amount in a unit. Immutable; amounts are never negative. */
public record Quantity(double amount, Unit unit) {

    public Quantity {
        Objects.requireNonNull(unit, "unit");
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("Quantity must be a non-negative number: " + amount);
        }
    }

    public static Quantity of(double amount, String unit) {
        return new Quantity(amount, Unit.parse(unit));
    }

    public Unit.Dimension dimension() {
        return unit.dimension();
    }

    public boolean isZero() {
        return amount == 0;
    }

    /** True when this quantity can be expressed in the given unit. */
    public boolean isCompatible(Unit target) {
        return isZero() || target.dimension() == dimension();
    }

    public Quantity convertTo(Unit target) {
        // Zero of anything is zero of anything else, so an empty pantry quantity can be
        // compared with a requirement in any unit.
        if (isZero()) {
            return new Quantity(0, target);
        }
        if (target.dimension() != dimension()) {
            throw new UnitException("Cannot convert " + unit + " (" + dimension() + ") to "
                    + target + " (" + target.dimension() + ")");
        }
        return new Quantity(amount * unit.baseFactor() / target.baseFactor(), target);
    }

    /** Sum, expressed in this quantity's unit. */
    public Quantity add(Quantity other) {
        return new Quantity(amount + other.convertTo(unit).amount, unit);
    }

    /** Remaining amount after taking {@code other} away, never below zero. */
    public Quantity subtract(Quantity other) {
        return new Quantity(Math.max(0, amount - other.convertTo(unit).amount), unit);
    }

    /** The same amount in a larger unit when that reads better: 1500 g becomes 1.5 kg, 2000 ml becomes 2 l. */
    public Quantity simplified() {
        if (unit == Unit.G && amount >= 1000) {
            return convertTo(Unit.KG);
        }
        if (unit == Unit.ML && amount >= 1000) {
            return convertTo(Unit.L);
        }
        return this;
    }

    /** At most two decimals, without trailing zeros: "12 unit", "1.5 kg", "0.67 cup". */
    @Override
    public String toString() {
        String number = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
        return number + " " + unit.symbol();
    }
}
