package io.github.ayech14.mealiq.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuantityTest {

    private static final double EPS = 1e-9;

    @Test
    void aliasesAreParsed() {
        assertEquals(Unit.G, Quantity.of(2, "Grams").unit());
        assertEquals(Unit.TBSP, Quantity.of(1, "tablespoons").unit());
        assertEquals(Unit.UNIT, Quantity.of(3, " pieces ").unit());
        assertEquals(Unit.FL_OZ, Quantity.of(1, "fl oz").unit());
    }

    @Test
    void unknownUnitIsRejected() {
        assertThrows(UnitException.class, () -> Quantity.of(1, "bunch"));
        assertFalse(Unit.isKnown("bunch"));
        assertTrue(Unit.isKnown("Cups"));
    }

    @Test
    void negativeAmountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Quantity.of(-1, "g"));
        assertThrows(IllegalArgumentException.class, () -> new Quantity(Double.NaN, Unit.G));
    }

    @ParameterizedTest
    @CsvSource({
        "1.5, kg, g, 1500",
        "1, lb, g, 453.59237",
        "1, cup, ml, 236.5882365",
        "3, tsp, tbsp, 1",
        "2, dozen, unit, 24",
    })
    void conversionWithinDimension(double amount, String unit, String target, double expected) {
        assertEquals(expected, Quantity.of(amount, unit).convertTo(Unit.parse(target)).amount(), 1e-6);
    }

    @Test
    void conversionAcrossDimensionsFails() {
        assertThrows(UnitException.class, () -> Quantity.of(1, "cup").convertTo(Unit.G));
    }

    @Test
    void zeroConvertsToAnyUnit() {
        assertEquals(new Quantity(0, Unit.UNIT), Quantity.of(0, "g").convertTo(Unit.UNIT));
    }

    @Test
    void addKeepsLeftUnit() {
        assertEquals(1500, Quantity.of(500, "g").add(Quantity.of(1, "kg")).amount(), EPS);
    }

    @Test
    void subtractNeverGoesBelowZero() {
        assertEquals(Quantity.of(50, "g"), Quantity.of(200, "g").subtract(Quantity.of(150, "g")));
        assertEquals(Quantity.of(0, "g"), Quantity.of(200, "g").subtract(Quantity.of(1, "kg")));
    }

    @Test
    void compatibilityAndDisplay() {
        Quantity litres = Quantity.of(2, "l");
        assertEquals(Unit.Dimension.VOLUME, litres.dimension());
        assertTrue(litres.isCompatible(Unit.CUP));
        assertFalse(litres.isCompatible(Unit.G));
        assertEquals("12 unit", Quantity.of(12, "unit").toString());
        assertEquals("1.5 kg", Quantity.of(1.5, "kg").toString());
    }
}
