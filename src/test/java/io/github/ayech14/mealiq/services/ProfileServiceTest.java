package io.github.ayech14.mealiq.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ayech14.mealiq.domain.ChangeEventType;
import io.github.ayech14.mealiq.domain.UserProfile;
import io.github.ayech14.mealiq.domain.WeightGoal;
import io.github.ayech14.mealiq.events.PlanRelevantChange;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProfileServiceTest {

    private InMemoryRepositories.Profiles repository;
    private InMemoryRepositories.RecordingObserver observer;
    private ProfileService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepositories.Profiles();
        observer = new InMemoryRepositories.RecordingObserver();
        service = new ProfileService(repository, new InMemoryRepositories.Catalogue());
        service.subscribe(observer);
    }

    private static UserProfile validProfile() {
        return new UserProfile().goal(WeightGoal.GAIN).weeklyBudget(new BigDecimal("100")).mealsPerDay(3)
                .maxCookMinutes(Map.of(DayOfWeek.MONDAY, 30));
    }

    @Test
    void saveAndGet() {
        UserProfile profile = service.updateProfile(validProfile());
        assertEquals(profile, service.getProfile(profile.id()));
        assertTrue(repository.rows.containsKey(profile.id()));
    }

    @Test
    void unknownProfileIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.getProfile(UUID.randomUUID()));
    }

    static Stream<Arguments> invalidProfiles() {
        return Stream.of(
                Arguments.of((Consumer<UserProfile>) p -> p.mealsPerDay(0), "mealsPerDay"),
                Arguments.of((Consumer<UserProfile>) p -> p.mealsPerDay(7), "mealsPerDay"),
                Arguments.of((Consumer<UserProfile>) p -> p.weeklyBudget(new BigDecimal("-1")), "weeklyBudget"),
                Arguments.of((Consumer<UserProfile>) p -> p.dailyCalorieTarget(300), "dailyCalorieTarget"),
                Arguments.of((Consumer<UserProfile>) p -> p.dailyProteinTargetG(900), "dailyProteinTargetG"),
                Arguments.of((Consumer<UserProfile>) p -> p.maxCookMinutes(Map.of(DayOfWeek.TUESDAY, -5)),
                        "maxCookMinutes"),
                Arguments.of((Consumer<UserProfile>) p -> p.dietaryRestrictions(Set.of("keto")),
                        "dietaryRestrictions"),
                Arguments.of((Consumer<UserProfile>) p -> p.preferredFoods(Set.of("Salmon"))
                        .dislikedFoods(Set.of("salmon")), "preferredFoods"));
    }

    @ParameterizedTest
    @MethodSource("invalidProfiles")
    void invalidValuesAreRejectedAndNotSaved(Consumer<UserProfile> change, String field) {
        UserProfile profile = validProfile();
        change.accept(profile);
        InvalidInputException error = assertThrows(InvalidInputException.class,
                () -> service.updateProfile(profile));
        assertTrue(error.errors().containsKey(field), error.errors().toString());
        assertTrue(repository.rows.isEmpty());
    }

    @Test
    void allFieldErrorsAreReportedTogether() {
        UserProfile profile = validProfile().mealsPerDay(0).weeklyBudget(new BigDecimal("-5"));
        InvalidInputException error = assertThrows(InvalidInputException.class,
                () -> service.updateProfile(profile));
        assertEquals(Set.of("mealsPerDay", "weeklyBudget"), error.errors().keySet());
    }

    @Test
    void emptyTimeLimitsAreValid() {
        assertTrue(service.validate(validProfile().maxCookMinutes(Map.of())).isEmpty());
    }

    @Test
    void preferredFoodWithAllergenIsFlaggedNotRejected() {
        UserProfile profile = validProfile().allergies(Set.of("peanut"))
                .preferredFoods(Set.of("peanut butter", "banana"));
        service.updateProfile(profile);
        List<String> warnings = service.preferenceWarnings(profile);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("peanut butter"));
    }

    @Test
    void preferredFoodAgainstDietIsFlagged() {
        UserProfile profile = validProfile().dietaryRestrictions(Set.of("vegetarian"))
                .preferredFoods(Set.of("salmon"));
        assertEquals(1, service.preferenceWarnings(profile).size());
    }

    @Nested
    class Events {

        @Test
        void firstSavePublishesNothing() {
            service.updateProfile(validProfile());
            assertTrue(observer.events.isEmpty());
        }

        @Test
        void unchangedSavePublishesNothing() {
            UserProfile profile = service.updateProfile(validProfile());
            service.updateProfile(profile.copy().weeklyBudget(new BigDecimal("100.00")));
            assertTrue(observer.events.isEmpty());
        }

        @Test
        void eachKindOfChangeGetsItsEvent() {
            UserProfile profile = service.updateProfile(validProfile());
            profile.weeklyBudget(new BigDecimal("80"))
                    .maxCookMinutes(Map.of(DayOfWeek.MONDAY, 15))
                    .allergies(Set.of("peanut"));
            service.updateProfile(profile);

            Map<ChangeEventType, PlanRelevantChange> byType = observer.events.stream()
                    .collect(Collectors.toMap(PlanRelevantChange::type, e -> e));
            assertEquals(Set.of(ChangeEventType.BUDGET_CHANGED, ChangeEventType.TIME_LIMIT_CHANGED,
                    ChangeEventType.PROFILE_CHANGED), byType.keySet());
            assertEquals(Map.of("changedFields", List.of("allergies")),
                    byType.get(ChangeEventType.PROFILE_CHANGED).details());
            assertTrue(observer.events.stream().allMatch(e -> e.profileId().equals(profile.id())));
        }

        @Test
        void rejectedUpdatePublishesNothing() {
            UserProfile profile = service.updateProfile(validProfile());
            profile.mealsPerDay(0);
            assertThrows(InvalidInputException.class, () -> service.updateProfile(profile));
            assertTrue(observer.events.isEmpty());
        }
    }
}
