package io.github.ayech14.mealiq.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.ChangeEventType;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Unit;
import io.github.ayech14.mealiq.events.PlanRelevantChange;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PantryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    private InMemoryRepositories.RecordingObserver observer;
    private PantryService service;
    private UUID profileId;

    @BeforeEach
    void setUp() {
        observer = new InMemoryRepositories.RecordingObserver();
        Clock clock = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new PantryService(new InMemoryRepositories.Pantries(), new InMemoryRepositories.Catalogue(),
                clock);
        service.subscribe(observer);
        profileId = UUID.randomUUID();
    }

    private PantryItem item(String name, double amount, String unit) {
        return service.makeItem(name, amount, unit, null);
    }

    @Test
    void addListAndUpdate() {
        service.updateItem(profileId, item("Egg", 12, "unit"));
        service.updateItem(profileId, item("white rice", 1, "kg"));
        service.updateItem(profileId, item("egg", 6, "pieces"));

        Pantry pantry = service.getPantry(profileId);
        Ingredient egg = item("egg", 0, "unit").ingredient();
        assertEquals(2, pantry.items().size());
        assertEquals(new Quantity(6, Unit.UNIT), pantry.availableQuantity(egg, TODAY));
    }

    @Test
    void settingZeroKeepsTheItemListed() {
        PantryItem egg = item("egg", 12, "unit");
        service.updateItem(profileId, egg);
        service.updateItem(profileId, item("egg", 0, "unit"));
        Pantry pantry = service.getPantry(profileId);
        assertEquals(0, pantry.availableQuantity(egg.ingredient(), TODAY).amount());
        assertTrue(pantry.items().containsKey(egg.ingredient().id()));
    }

    @Test
    void remove() {
        PantryItem spinach = item("spinach", 200, "g");
        service.updateItem(profileId, spinach);
        service.removeItem(profileId, spinach.ingredient().id());
        assertTrue(service.getPantry(profileId).items().isEmpty());
        assertThrows(NotFoundException.class, () -> service.removeItem(profileId, spinach.ingredient().id()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "egg          | -1 | unit    | quantity",
        "egg          |  2 | bunch   | unit",
        "dragon fruit |  1 | unit    | ingredient",
        "dragon fruit | -3 | handful | ingredient;quantity;unit"})
    void badInputIsRejectedPerField(String name, double amount, String unit, String fields) {
        InvalidInputException error = assertThrows(InvalidInputException.class,
                () -> service.makeItem(name, amount, unit, null));
        assertEquals(Set.of(fields.split(";")), error.errors().keySet());
    }

    @Test
    void ingredientOutsideTheCatalogueIsRejected() {
        PantryItem homemade = new PantryItem(Ingredient.of("homemade sauce", "condiment"),
                Quantity.of(1, "cup"), null);
        assertThrows(InvalidInputException.class, () -> service.updateItem(profileId, homemade));
        assertTrue(service.getPantry(profileId).items().isEmpty());
        assertTrue(observer.events.isEmpty());
    }

    @Test
    void expiredItemsStayListedButAreNotAvailable() {
        PantryItem milk = service.makeItem("milk", 1, "l", LocalDate.of(2026, 10, 20));
        service.updateItem(profileId, milk);
        assertEquals(List.of(milk), service.expiredItems(profileId));
        assertEquals(0, service.getPantry(profileId).availableQuantity(milk.ingredient(), TODAY).amount());
    }

    @Nested
    class Events {

        @Test
        void updatePublishesPantryChangedWithDetails() {
            service.updateItem(profileId, item("egg", 12, "unit"));
            service.updateItem(profileId, item("egg", 4, "unit"));
            PlanRelevantChange first = observer.events.get(0);
            PlanRelevantChange second = observer.events.get(1);
            assertSame(ChangeEventType.PANTRY_CHANGED, first.type());
            assertEquals(profileId, first.profileId());
            assertNull(first.details().get("previousQuantity"));
            assertEquals("12 unit", second.details().get("previousQuantity"));
            assertEquals("4 unit", second.details().get("quantity"));
        }

        @Test
        void removePublishes() {
            PantryItem egg = item("egg", 12, "unit");
            service.updateItem(profileId, egg);
            service.removeItem(profileId, egg.ingredient().id());
            PlanRelevantChange last = observer.events.getLast();
            assertEquals("removed", last.details().get("action"));
            assertNull(last.details().get("quantity"));
        }

        @Test
        void unsubscribedObserverIsNotNotified() {
            service.unsubscribe(observer);
            service.updateItem(profileId, item("egg", 1, "unit"));
            assertTrue(observer.events.isEmpty());
        }

        @Test
        void failingObserverDoesNotBlockOthersOrTheUpdate() {
            service.unsubscribe(observer);
            service.subscribe(event -> {
                throw new IllegalStateException("boom");
            });
            service.subscribe(observer);
            Pantry pantry = service.updateItem(profileId, item("egg", 1, "unit"));
            assertEquals(1, pantry.items().size());
            assertEquals(1, observer.events.size());
        }
    }

    @Nested
    class EverydayNames {

        private List<String> names(String text) {
            return service.matchIngredients(text).stream().map(Ingredient::name).toList();
        }

        @Test
        void aGeneralNameListsEveryKind() {
            assertEquals(List.of("beef sirloin", "ground beef"), names("beef"));
            assertTrue(names("chicken").containsAll(List.of("chicken breast", "chicken thigh", "ground chicken")));
        }

        @Test
        void exactAndPluralNamesGiveOneIngredient() {
            assertEquals(List.of("egg"), names("Eggs"));
            assertEquals(List.of("egg"), names("egg"), "egg white and eggplant are not eggs");
            assertEquals(List.of("ground beef"), names("  ground   beef "));
        }

        @Test
        void unknownNamesMatchNothing() {
            assertEquals(List.of(), names("unobtainium"));
            assertEquals(List.of(), names(" "));
        }

        @Test
        void pluralNamesCanBeSaved() {
            assertEquals("egg", item("eggs", 6, "unit").ingredient().name());
        }

        @Test
        void aGeneralNameIsRejectedWithTheKindsToChooseFrom() {
            InvalidInputException error = assertThrows(InvalidInputException.class, () -> item("beef", 450, "g"));
            assertEquals("'beef' is not in the ingredient catalogue; did you mean beef sirloin, ground beef?",
                    error.errors().get("ingredient"));
        }
    }
}
