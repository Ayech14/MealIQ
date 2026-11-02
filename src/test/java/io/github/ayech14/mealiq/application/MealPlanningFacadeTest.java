package io.github.ayech14.mealiq.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.contracts.AgentResponse;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.ResponseStatus;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.services.InMemoryRepositories;
import io.github.ayech14.mealiq.services.InvalidInputException;
import io.github.ayech14.mealiq.services.NotFoundException;
import io.github.ayech14.mealiq.services.PantryService;
import io.github.ayech14.mealiq.services.ProfileService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MealPlanningFacadeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    private InMemoryRepositories.RecordingObserver observer;
    private MealPlanningFacade facade;
    private UUID profileId;

    @BeforeEach
    void setUp() {
        InMemoryRepositories.Catalogue catalogue = new InMemoryRepositories.Catalogue();
        Clock clock = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        PantryService pantryService = new PantryService(new InMemoryRepositories.Pantries(), catalogue, clock);
        observer = new InMemoryRepositories.RecordingObserver();
        pantryService.subscribe(observer);
        facade = new MealPlanningFacade(new ProfileService(new InMemoryRepositories.Profiles(), catalogue),
                pantryService, catalogue);
        profileId = MealPlanningFacade.localProfileId("default");
    }

    @Test
    void localProfileIdsAreStableAndIgnoreCase() {
        assertEquals(MealPlanningFacade.localProfileId(" Alex "), MealPlanningFacade.localProfileId("alex"));
        assertNotEquals(MealPlanningFacade.localProfileId("alex"), MealPlanningFacade.localProfileId("sam"));
    }

    @Test
    void profileIsSavedThenFound() {
        assertThrows(NotFoundException.class, () -> facade.getProfile(profileId));
        facade.updateProfile(new UserProfile(profileId).weeklyBudget(new BigDecimal("80")));
        assertEquals(new BigDecimal("80"), facade.getProfile(profileId).weeklyBudget());
    }

    @Test
    void invalidProfileIsNotSaved() {
        assertThrows(InvalidInputException.class, () -> facade.updateProfile(new UserProfile(profileId).mealsPerDay(9)));
        assertThrows(NotFoundException.class, () -> facade.getProfile(profileId));
    }

    @Test
    void profileWarningsComeFromTheService() {
        UserProfile profile = new UserProfile(profileId).dietaryRestrictions(Set.of("vegan"))
                .preferredFoods(Set.of("cheddar cheese"));
        assertEquals(1, facade.profileWarnings(profile).size());
    }

    @Test
    void updatePantryReportsSuccessAndPublishes() {
        AgentResponse response = facade.updatePantry(profileId, facade.pantryItem("Egg", 6, "unit", null));
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertEquals("Pantry updated: egg 6 unit.", response.rationale());
        assertEquals(1, facade.getPantry(profileId).items().size());
        assertEquals(1, observer.events.size());
    }

    @Test
    void badPantryInputReportsEveryField() {
        InvalidInputException error = assertThrows(InvalidInputException.class,
                () -> facade.pantryItem("unobtainium", -1, "bushel", null));
        assertEquals(Set.of("ingredient", "quantity", "unit"), error.errors().keySet());
    }

    @Test
    void removeByNameAndUnknownNameIsNotFound() {
        facade.updatePantry(profileId, facade.pantryItem("milk", 1, "l", null));
        assertThrows(NotFoundException.class, () -> facade.removePantryItem(profileId, "egg"));
        assertTrue(facade.removePantryItem(profileId, " MILK ").items().isEmpty());
    }

    @Test
    void expiredItemsUseTheServiceClock() {
        facade.updatePantry(profileId, facade.pantryItem("milk", 1, "l", TODAY.minusDays(1)));
        facade.updatePantry(profileId, facade.pantryItem("egg", 6, "unit", TODAY));
        List<PantryItem> expired = facade.expiredPantryItems(profileId);
        assertEquals(List.of("milk"), expired.stream().map(i -> i.ingredient().name()).toList());
    }

    @Test
    void searchIngredientsMatchesPartOfTheName() {
        List<String> names = facade.searchIngredients(" Milk").stream().map(Ingredient::name).toList();
        assertTrue(names.containsAll(List.of("almond milk", "milk", "skim milk", "soy milk")), names.toString());
        assertTrue(names.stream().allMatch(n -> n.contains("milk")));
        assertEquals(facade.searchIngredients(null).size(), facade.searchIngredients("").size());
        assertTrue(facade.searchIngredients("unobtainium").isEmpty());
    }
}
