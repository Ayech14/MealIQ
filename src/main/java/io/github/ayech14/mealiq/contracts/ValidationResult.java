package io.github.ayech14.mealiq.contracts;

import java.util.ArrayList;
import java.util.List;

/** Outcome of a deterministic check: violations make it invalid, warnings do not. */
public record ValidationResult(boolean valid, List<String> violations, List<String> warnings) {

    public ValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (valid && !violations.isEmpty()) {
            throw new IllegalArgumentException("A valid result cannot have violations");
        }
    }

    public static ValidationResult ok(String... warnings) {
        return new ValidationResult(true, List.of(), List.of(warnings));
    }

    public static ValidationResult failed(String... violations) {
        return new ValidationResult(false, List.of(violations), List.of());
    }

    /** Combines two results; the merge is valid only if both are. */
    public ValidationResult merge(ValidationResult other) {
        List<String> allViolations = new ArrayList<>(violations);
        allViolations.addAll(other.violations);
        List<String> allWarnings = new ArrayList<>(warnings);
        allWarnings.addAll(other.warnings);
        return new ValidationResult(valid && other.valid, allViolations, allWarnings);
    }
}
