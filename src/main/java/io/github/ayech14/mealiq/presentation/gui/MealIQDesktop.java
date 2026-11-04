package io.github.ayech14.mealiq.presentation.gui;

import javafx.application.Application;

/**
 * Starts the GUI. A separate main class is needed when JavaFX is on the class path rather
 * than the module path: the JVM cannot launch an Application subclass directly then.
 */
public final class MealIQDesktop {

    private MealIQDesktop() {
    }

    public static void main(String[] args) {
        Application.launch(MealPlanningGUI.class, args);
    }
}
