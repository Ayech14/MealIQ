package io.github.ayech14.mealiq.services;

import io.github.ayech14.mealiq.domain.ChangeEventType;
import io.github.ayech14.mealiq.domain.Ingredient;
import io.github.ayech14.mealiq.domain.Pantry;
import io.github.ayech14.mealiq.domain.PantryItem;
import io.github.ayech14.mealiq.domain.Quantity;
import io.github.ayech14.mealiq.domain.Unit;
import io.github.ayech14.mealiq.events.ObserverList;
import io.github.ayech14.mealiq.events.PlanImpactObserver;
import io.github.ayech14.mealiq.events.PlanRelevantChange;
import io.github.ayech14.mealiq.events.PlanRelevantChangePublisher;
import io.github.ayech14.mealiq.persistence.IngredientRepository;
import io.github.ayech14.mealiq.persistence.PantryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Maintains the user's pantry inventory (F02, UC02), and publishes each change. */
public class PantryService implements PlanRelevantChangePublisher {

    private final PantryRepository pantryRepository;
    private final IngredientRepository ingredientRepository;
    private final Clock clock;
    private final ObserverList observers = new ObserverList();

    public PantryService(PantryRepository pantryRepository, IngredientRepository ingredientRepository) {
        this(pantryRepository, ingredientRepository, Clock.systemDefaultZone());
    }

    /** The clock decides which items count as expired; tests pass a fixed one. */
    public PantryService(PantryRepository pantryRepository, IngredientRepository ingredientRepository, Clock clock) {
        this.pantryRepository = pantryRepository;
        this.ingredientRepository = ingredientRepository;
        this.clock = clock;
    }

    public Pantry getPantry(UUID profileId) {
        return pantryRepository.findByProfileId(profileId);
    }

    /** Builds a PantryItem from interface input, reporting every bad field together (UC02 3a). */
    public PantryItem makeItem(String ingredientName, double amount, String unit, LocalDate expiryDate) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Ingredient> matches = matchIngredients(ingredientName);
        Optional<Ingredient> ingredient = matches.size() == 1 && sameName(matches.getFirst(), ingredientName)
                ? Optional.of(matches.getFirst()) : Optional.empty();
        if (ingredient.isEmpty()) {
            errors.put("ingredient", "'" + ingredientName + "' is not in the ingredient catalogue"
                    + (matches.isEmpty() ? "" : "; did you mean " + String.join(", ",
                            matches.stream().map(Ingredient::name).toList()) + "?"));
        }
        if (amount < 0 || Double.isNaN(amount)) {
            errors.put("quantity", "cannot be negative");
        }
        if (!Unit.isKnown(unit)) {
            errors.put("unit", "unknown unit '" + unit + "'");
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        return new PantryItem(ingredient.get(), Quantity.of(amount, unit), expiryDate);
    }

    /**
     * Catalogue ingredients for a name as people say it. An exact name, or its singular
     * ("eggs", "tomatoes"), gives that one ingredient. Otherwise every ingredient whose name
     * contains all the words is returned, so "beef" gives ground beef and beef sirloin, and
     * the user (or the agent) picks one. Empty when nothing matches.
     */
    public List<Ingredient> matchIngredients(String text) {
        String wanted = normalize(text);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<Ingredient> all = ingredientRepository.findAll();
        for (String form : List.of(wanted, singular(wanted))) {
            Optional<Ingredient> exact = all.stream().filter(i -> i.name().equals(form)).findFirst();
            if (exact.isPresent()) {
                return List.of(exact.get());
            }
        }
        List<String> words = Arrays.stream(wanted.split(" ")).map(PantryService::singular).toList();
        return all.stream().filter(i -> {
            List<String> nameWords = Arrays.asList(i.name().split(" "));
            return words.stream().allMatch(w -> nameWords.stream().anyMatch(n -> singular(n).equals(w)));
        }).sorted(Comparator.comparing(Ingredient::name)).toList();
    }

    private static boolean sameName(Ingredient ingredient, String text) {
        String wanted = normalize(text);
        return ingredient.name().equals(wanted) || ingredient.name().equals(singular(wanted));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** A plain English singular: "tomatoes" -> "tomato", "eggs" -> "egg"; other words stay. */
    static String singular(String word) {
        if (word.endsWith("oes") || word.endsWith("ches") || word.endsWith("shes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("ies") && word.length() > 4) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    /**
     * Adds or replaces one item, saves, and publishes PANTRY_CHANGED. A quantity of zero
     * keeps the item listed as out of stock; use {@link #removeItem} to delete it.
     */
    public Pantry updateItem(UUID profileId, PantryItem item) {
        if (ingredientRepository.findById(item.ingredient().id()).isEmpty()) {
            throw InvalidInputException.of("ingredient",
                    "'" + item.ingredient().name() + "' is not in the ingredient catalogue");
        }
        Pantry pantry = pantryRepository.findByProfileId(profileId);
        PantryItem previous = pantry.items().get(item.ingredient().id());
        pantry.updateItem(item);
        pantryRepository.save(pantry);
        publish(event(profileId, "updated", item, previous));
        return pantry;
    }

    public Pantry removeItem(UUID profileId, UUID ingredientId) {
        Pantry pantry = pantryRepository.findByProfileId(profileId);
        PantryItem previous = pantry.items().get(ingredientId);
        if (previous == null) {
            throw new NotFoundException("That ingredient is not in the pantry");
        }
        pantry.removeItem(ingredientId);
        pantryRepository.save(pantry);
        publish(event(profileId, "removed", null, previous));
        return pantry;
    }

    /** Items kept in the list but excluded from available stock (UC02 3b). */
    public List<PantryItem> expiredItems(UUID profileId) {
        LocalDate today = LocalDate.now(clock);
        return getPantry(profileId).items().values().stream().filter(i -> i.isExpired(today)).toList();
    }

    @Override
    public void subscribe(PlanImpactObserver observer) {
        observers.add(observer);
    }

    @Override
    public void unsubscribe(PlanImpactObserver observer) {
        observers.remove(observer);
    }

    @Override
    public void publish(PlanRelevantChange event) {
        observers.notifyAll(event);
    }

    private static PlanRelevantChange event(UUID profileId, String action, PantryItem item, PantryItem previous) {
        Ingredient subject = (item != null ? item : previous).ingredient();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action);
        details.put("ingredientId", subject.id());
        details.put("ingredient", subject.name());
        details.put("previousQuantity", previous == null ? null : previous.quantity().toString());
        details.put("quantity", item == null ? null : item.quantity().toString());
        return new PlanRelevantChange(ChangeEventType.PANTRY_CHANGED, profileId, details);
    }
}
