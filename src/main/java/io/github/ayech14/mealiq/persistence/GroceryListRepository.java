package io.github.ayech14.mealiq.persistence;

import io.github.ayech14.mealiq.domain.GroceryList;
import java.util.Optional;
import java.util.UUID;

public interface GroceryListRepository {

    Optional<GroceryList> findByPlanId(UUID planId);

    void save(GroceryList groceryList);
}
